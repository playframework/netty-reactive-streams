package com.typesafe.netty.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.*;
import org.reactivestreams.Publisher;

/**
 * Handler that converts written {@link StreamedHttpRequest} messages into {@link HttpRequest} messages
 * followed by {@link HttpContent} messages and reads {@link HttpResponse} messages followed by
 * {@link HttpContent} messages and produces {@link StreamedHttpResponse} messages.
 *
 * This allows request and response bodies to be handled using reactive streams.
 *
 * There are two types of messages that this handler accepts for writing, {@link StreamedHttpRequest} and
 * {@link FullHttpRequest}. Writing any other messages may potentially lead to HTTP message mangling.
 *
 * There are two types of messages that this handler will send down the chain, {@link StreamedHttpResponse},
 * and {@link FullHttpResponse}. If {@link io.netty.channel.ChannelOption#AUTO_READ} is false for the channel,
 * then any {@link StreamedHttpResponse} messages <em>must</em> be subscribed to consume the body, otherwise
 * it's possible that no read will be done of the messages.
 *
 * As long as messages are returned in the order that they arrive, this handler implicitly supports HTTP
 * pipelining.
 */
public class HttpStreamsClientHandler extends HttpStreamsHandler<HttpResponse, HttpRequest> {

    private int inFlight = 0;
    private ChannelPromise closeOnZeroInFlight = null;

    public HttpStreamsClientHandler() {
        super(HttpResponse.class, HttpRequest.class);
    }

    @Override
    protected boolean hasBody(HttpResponse response) {
        if (response.getStatus().code() >= 100 && response.getStatus().code() < 200) {
            return false;
        }

        if (response.getStatus().equals(HttpResponseStatus.NO_CONTENT) ||
                response.getStatus().equals(HttpResponseStatus.NOT_MODIFIED)) {
            return false;
        }

        if (HttpHeaders.isTransferEncodingChunked(response)) {
            return true;
        }


        if (HttpHeaders.isContentLengthSet(response)) {
            return HttpHeaders.getContentLength(response) > 0;
        }

        return true;
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise future) throws Exception {
        if (inFlight == 0) {
            ctx.close(future);
        } else {
            closeOnZeroInFlight = future;
        }
    }

    @Override
    protected void consumedInMessage(ChannelHandlerContext ctx) {
        inFlight--;
        if (inFlight == 0 && closeOnZeroInFlight != null) {
            ctx.close(closeOnZeroInFlight);
        }
    }

    @Override
    protected void receivedOutMessage(ChannelHandlerContext ctx) {
        inFlight++;
    }

    @Override
    protected HttpResponse createEmptyMessage(HttpResponse response) {
        return new EmptyHttpResponse(response);
    }

    @Override
    protected HttpResponse createStreamedMessage(HttpResponse response, Publisher<HttpContent> stream) {
        return new DelegateStreamedHttpResponse(response, stream);
    }

}
