/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.http.api.server.ws;

import org.mule.runtime.http.api.ws.WebSocketMessage;

import com.google.common.annotations.Beta;

@Beta
public interface WebSocketMessageHandler {

  void onMessage(WebSocketMessage message);
}
