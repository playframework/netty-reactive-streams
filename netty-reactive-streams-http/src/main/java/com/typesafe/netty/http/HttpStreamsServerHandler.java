package com.typesafe.netty.http;

import io.netty.handler.codec.http.*;
import org.reactivestreams.Publisher;

/**
 * Handler that reads {@link HttpRequest} messages followed by {@link HttpContent} messages and produces
 * {@link StreamedHttpRequest} messages, and converts written {@link StreamedHttpResponse} messages into
 * {@link HttpResponse} messages followed by {@link HttpContent} messages.
 *
 * This allows request and response bodies to be handled using reactive streams.
 *
 * There are two types of messages that this handler will send down the chain, {@link StreamedHttpRequest},
 * and {@link FullHttpRequest}. If {@link io.netty.channel.ChannelOption#AUTO_READ} is false for the channel,
 * then any {@link StreamedHttpRequest} messages <em>must</em> be subscribed to consume the body, otherwise
 * it's possible that no read will be done of the messages.
 *
 * There are two types of messages that this handler accepts for writing, {@link StreamedHttpResponse} and
 * {@link FullHttpResponse}. Writing any other messages may potentially lead to HTTP message mangling.
 *
 * As long as messages are returned in the order that they arrive, this handler implicitly supports HTTP
 * pipelining.
 */
public class HttpStreamsServerHandler extends HttpStreamsHandler<HttpRequest, HttpResponse> {

    public HttpStreamsServerHandler() {
        super(HttpRequest.class, HttpResponse.class);
    }

    @Override
    protected boolean hasBody(HttpRequest request) {
        // Http requests don't have a body if they define 0 content length, or no content length and no transfer
        // encoding
        return HttpHeaders.getContentLength(request, 0) != 0 || HttpHeaders.isTransferEncodingChunked(request);
    }

    @Override
    protected HttpRequest createEmptyMessage(HttpRequest request) {
        return new EmptyHttpRequest(request);
    }

    @Override
    protected HttpRequest createStreamedMessage(HttpRequest httpRequest, Publisher<HttpContent> stream) {
        return new DelegateStreamedHttpRequest(httpRequest, stream);
    }
}
