package com.typesafe.netty.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
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

    private int inFlight = 0;
    private HttpVersion continueExpected = null;
    private boolean sendContinue = false;
    private boolean close = false;

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

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // Set to false, since if it was true, and the client is sending data, then the
        // client must no longer be expecting it (due to a timeout, for example).
        continueExpected = null;
        sendContinue = false;

        if (msg instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) msg;
            if (HttpHeaders.is100ContinueExpected(request)) {
                continueExpected = request.getProtocolVersion();
            }
        }
        super.channelRead(ctx, msg);
    }

    @Override
    protected void receivedInMessage(ChannelHandlerContext ctx) {
        inFlight++;
    }

    @Override
    protected void sentOutMessage(ChannelHandlerContext ctx) {
        inFlight--;
        if (inFlight == 1 && continueExpected != null && sendContinue) {
            ctx.writeAndFlush(new DefaultFullHttpResponse(continueExpected, HttpResponseStatus.CONTINUE));
            sendContinue = false;
            continueExpected = null;
        }

        if (close) {
            ctx.close();
        }
    }

    @Override
    protected void unbufferedWrite(ChannelHandlerContext ctx, HttpStreamsHandler<HttpRequest, HttpResponse>.Outgoing out) {
        if (inFlight == 1 && continueExpected != null) {
            HttpHeaders.setKeepAlive(out.message, false);
            close = true;
            continueExpected = null;
        }
        if (!HttpHeaders.isContentLengthSet(out.message) && !HttpHeaders.isTransferEncodingChunked(out.message)) {
            HttpHeaders.setKeepAlive(out.message, false);
            close = true;
        }
        super.unbufferedWrite(ctx, out);
    }

    @Override
    protected void bodyRequested(ChannelHandlerContext ctx) {
        if (continueExpected != null) {
            if (inFlight == 1) {
                ctx.writeAndFlush(new DefaultFullHttpResponse(continueExpected, HttpResponseStatus.CONTINUE));
                continueExpected = null;
            } else {
                sendContinue = true;
            }
        }
    }
}
