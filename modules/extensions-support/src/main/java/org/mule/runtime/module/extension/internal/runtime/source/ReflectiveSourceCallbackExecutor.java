/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.runtime.source;

import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.initialiseIfNeeded;
import static org.mule.runtime.core.api.rx.Exceptions.wrapFatal;
import static org.mule.runtime.core.internal.util.rx.ImmediateScheduler.IMMEDIATE_SCHEDULER;
import static org.mule.runtime.module.extension.internal.ExtensionProperties.BACK_PRESSURE_ACTION_CONTEXT_PARAM;
import static org.mule.runtime.module.extension.internal.ExtensionProperties.SOURCE_CALLBACK_CONTEXT_PARAM;
import static org.mule.runtime.module.extension.internal.ExtensionProperties.SOURCE_COMPLETION_CALLBACK_PARAM;
import static reactor.core.publisher.Mono.create;
import static reactor.core.publisher.Mono.empty;
import static reactor.core.publisher.Mono.error;

import org.mule.runtime.api.component.Component;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.api.meta.model.ExtensionModel;
import org.mule.runtime.api.meta.model.parameter.ParameterGroupModel;
import org.mule.runtime.api.meta.model.source.SourceModel;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.streaming.CursorProviderFactory;
import org.mule.runtime.core.api.streaming.StreamingManager;
import org.mule.runtime.extension.api.runtime.config.ConfigurationInstance;
import org.mule.runtime.extension.api.runtime.operation.ExecutionContext;
import org.mule.runtime.extension.api.runtime.source.BackPressureAction;
import org.mule.runtime.extension.api.runtime.source.Source;
import org.mule.runtime.extension.api.runtime.source.SourceCallbackContext;
import org.mule.runtime.extension.api.runtime.source.SourceCompletionCallback;
import org.mule.runtime.module.extension.api.runtime.privileged.ExecutionContextAdapter;
import org.mule.runtime.module.extension.internal.loader.java.property.SourceCallbackModelProperty;
import org.mule.runtime.module.extension.internal.runtime.DefaultExecutionContext;
import org.mule.runtime.module.extension.internal.runtime.execution.ReflectiveMethodComponentExecutor;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Implementation of {@link SourceCallbackExecutor} which uses reflection to execute the callback through a {@link Method}
 *
 * @since 4.0
 */
class ReflectiveSourceCallbackExecutor implements SourceCallbackExecutor {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReflectiveSourceCallbackExecutor.class);
  private final ExtensionModel extensionModel;
  private final Optional<ConfigurationInstance> configurationInstance;
  private final SourceModel sourceModel;
  private final CursorProviderFactory cursorProviderFactory;
  private final StreamingManager streamingManager;
  private final MuleContext muleContext;
  private final boolean async;
  private final ReflectiveMethodComponentExecutor<SourceModel> executor;
  private final Component component;

  /**
   * Creates a new instance
   *
   * @param extensionModel        the {@link ExtensionModel} of the owning component
   * @param configurationInstance an {@link Optional} {@link ConfigurationInstance} in case the component requires a config
   * @param sourceModel           the model of the {@code source}
   * @param source                a {@link Source} instance
   * @param method                the method to be executed
   * @param cursorProviderFactory the {@link CursorProviderFactory} that was configured on the owning source
   * @param streamingManager      the application's {@link StreamingManager}
   * @param component             the source {@link Component}
   * @param muleContext           the current {@link MuleContext}
   * @param sourceCallbackModel   the callback's model
   */
  public ReflectiveSourceCallbackExecutor(ExtensionModel extensionModel,
                                          Optional<ConfigurationInstance> configurationInstance,
                                          SourceModel sourceModel,
                                          Object source,
                                          Method method,
                                          CursorProviderFactory cursorProviderFactory,
                                          StreamingManager streamingManager,
                                          Component component,
                                          MuleContext muleContext,
                                          SourceCallbackModelProperty sourceCallbackModel) {

    this.extensionModel = extensionModel;
    this.configurationInstance = configurationInstance;
    this.sourceModel = sourceModel;
    this.cursorProviderFactory = cursorProviderFactory;
    this.streamingManager = streamingManager;
    this.component = component;
    this.muleContext = muleContext;
    executor = new ReflectiveMethodComponentExecutor<>(getAllGroups(sourceModel, method, sourceCallbackModel), method, source);
    try {
      initialiseIfNeeded(executor, muleContext);
    } catch (InitialisationException e) {
      throw new MuleRuntimeException(e);
    }
    async = Stream.of(method.getParameterTypes()).anyMatch(p -> SourceCompletionCallback.class.equals(p));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Publisher<Void> execute(CoreEvent event, Map<String, Object> parameters, SourceCallbackContext context) {
    if (async) {
      return create(sink -> {
        final ExecutionContext<SourceModel> executionContext =
            createExecutionContext(event, parameters, context, new ReactorSourceCompletionCallback(sink));
        try {
          executor.execute(executionContext);
        } catch (Throwable t) {
          sink.error(wrapFatal(t));
        }
      });
    }

    try {
      executor.execute(createExecutionContext(event, parameters, context, null));
      return empty();
    } catch (Throwable t) {
      return error(wrapFatal(t));
    }
  }

  private ExecutionContext<SourceModel> createExecutionContext(CoreEvent event,
                                                               Map<String, Object> parameters,
                                                               SourceCallbackContext callbackContext,
                                                               SourceCompletionCallback sourceCompletionCallback) {
    ExecutionContextAdapter<SourceModel> executionContext = new DefaultExecutionContext<>(extensionModel,
                                                                                          configurationInstance,
                                                                                          parameters,
                                                                                          sourceModel,
                                                                                          event,
                                                                                          cursorProviderFactory,
                                                                                          streamingManager,
                                                                                          component,
                                                                                          null,
                                                                                          IMMEDIATE_SCHEDULER,
                                                                                          muleContext);

    executionContext.setVariable(SOURCE_CALLBACK_CONTEXT_PARAM, callbackContext);
    if (sourceCompletionCallback != null) {
      executionContext.setVariable(SOURCE_COMPLETION_CALLBACK_PARAM, sourceCompletionCallback);
    }

    callbackContext.<BackPressureAction>getVariable(BACK_PRESSURE_ACTION_CONTEXT_PARAM)
        .ifPresent(action -> executionContext.setVariable(BACK_PRESSURE_ACTION_CONTEXT_PARAM, action));

    return executionContext;
  }

  private List<ParameterGroupModel> getAllGroups(SourceModel model, Method method,
                                                 SourceCallbackModelProperty sourceCallbackModel) {
    List<ParameterGroupModel> callbackParameters = sourceCallbackModel.getOnSuccessMethod().filter(method::equals)
        .map(m -> sourceModel.getSuccessCallback().get().getParameterGroupModels())
        .orElseGet(() -> sourceCallbackModel.getOnErrorMethod().filter(method::equals)
            .map(m -> sourceModel.getErrorCallback().get().getParameterGroupModels())
            .orElseGet(() -> sourceModel.getTerminateCallback().get().getParameterGroupModels()));

    return ImmutableList.<ParameterGroupModel>builder()
        .addAll(model.getParameterGroupModels())
        .addAll(callbackParameters).build();
  }
}
