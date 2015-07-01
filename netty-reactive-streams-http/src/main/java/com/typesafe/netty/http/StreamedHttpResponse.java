package com.typesafe.netty.http;

import io.netty.handler.codec.http.HttpResponse;

/**
 * Combines {@link HttpResponse} and {@link StreamedHttpMessage} into one
 * message. So it represents an http repsonse with a stream of
 * {@link io.netty.handler.codec.http.HttpContent} messages that can be subscribed to.
 */
public interface StreamedHttpResponse extends HttpResponse, StreamedHttpMessage {
}
