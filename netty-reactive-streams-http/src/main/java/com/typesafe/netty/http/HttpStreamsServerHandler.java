package com.typesafe.netty.http;

import com.typesafe.netty.CancelledSubscriber;
import com.typesafe.netty.HandlerPublisher;
import com.typesafe.netty.HandlerSubscriber;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import org.reactivestreams.Publisher;

import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

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
 * There are three types of messages that this handler accepts for writing, {@link StreamedHttpResponse},
 * {@link WebSocketHttpResponse} and {@link FullHttpResponse}. Writing any other messages may potentially
 * lead to HTTP message mangling.
 *
 * As long as messages are returned in the order that they arrive, this handler implicitly supports HTTP
 * pipelining.
 */
public class HttpStreamsServerHandler extends HttpStreamsHandler<HttpRequest, HttpResponse> {

    private HttpRequest lastRequest = null;
    private int inFlight = 0;
    private boolean continueExpected = true;
    private boolean sendContinue = false;
    private boolean close = false;

    private final List<ChannelHandler> dependentHandlers;

    public HttpStreamsServerHandler() {
        this(Collections.<ChannelHandler>emptyList());
    }

    /**
     * Create a new handler that is depended on by the given handlers.
     *
     * The list of dependent handlers will be removed from the chain when this handler is removed from the chain,
     * for example, when the connection is upgraded to use websockets. This is useful, for example, for removing
     * the reactive streams publisher/subscriber from the chain in that event.
     *
     * @param dependentHandlers The handlers that depend on this handler.
     */
    public HttpStreamsServerHandler(List<ChannelHandler> dependentHandlers) {
        super(HttpRequest.class, HttpResponse.class);
        this.dependentHandlers = dependentHandlers;
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
        continueExpected = false;
        sendContinue = false;

        if (msg instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) msg;
            lastRequest = request;
            if (HttpHeaders.is100ContinueExpected(request)) {
                continueExpected = true;
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
        if (inFlight == 1 && continueExpected && sendContinue) {
            ctx.writeAndFlush(new DefaultFullHttpResponse(lastRequest.getProtocolVersion(), HttpResponseStatus.CONTINUE));
            sendContinue = false;
            continueExpected = false;
        }

        if (close) {
            ctx.close();
        }
    }

    @Override
    protected void unbufferedWrite(ChannelHandlerContext ctx, HttpStreamsHandler<HttpRequest, HttpResponse>.Outgoing out) {

        if (out.message instanceof WebSocketHttpResponse) {
            handleWebSocketResponse(ctx, out);
        } else {
            String connection = out.message.headers().get(HttpHeaders.Names.CONNECTION);
            if (lastRequest.getProtocolVersion().isKeepAliveDefault()) {
                if ("close".equalsIgnoreCase(connection)) {
                    close = true;
                }
            } else {
                if (!"keep-alive".equalsIgnoreCase(connection)) {
                    close = true;
                }
            }
            if (inFlight == 1 && continueExpected) {
                HttpHeaders.setKeepAlive(out.message, false);
                close = true;
                continueExpected = false;
            }
            if (!HttpHeaders.isContentLengthSet(out.message) && !HttpHeaders.isTransferEncodingChunked(out.message)) {
                HttpHeaders.setKeepAlive(out.message, false);
                close = true;
            }
            super.unbufferedWrite(ctx, out);
        }
    }

    private void handleWebSocketResponse(ChannelHandlerContext ctx, Outgoing out) {
        WebSocketHttpResponse response = (WebSocketHttpResponse) out.message;
        WebSocketServerHandshaker handshaker = response.handshakerFactory().newHandshaker(lastRequest);

        if (handshaker == null) {
            HttpResponse res = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.UPGRADE_REQUIRED);
            res.headers().set(HttpHeaders.Names.SEC_WEBSOCKET_VERSION, WebSocketVersion.V13.toHttpHeaderValue());
            HttpHeaders.setContentLength(res, 0);
            super.unbufferedWrite(ctx, new Outgoing(res, out.promise));
            response.subscribe(new CancelledSubscriber<>());
        } else {
            // First, insert new handlers in the chain after us for handling the websocket
            ChannelPipeline pipeline = ctx.pipeline();
            HandlerPublisher<WebSocketFrame> publisher = new HandlerPublisher<>(ctx.executor(), WebSocketFrame.class);
            HandlerSubscriber<WebSocketFrame> subscriber = new HandlerSubscriber<>(ctx.executor());
            pipeline.addAfter(ctx.executor(), ctx.name(), "websocket-subscriber", subscriber);
            pipeline.addAfter(ctx.executor(), ctx.name(), "websocket-publisher", publisher);

            // Now remove ourselves from the chain
            ctx.pipeline().remove(ctx.name());

            // Now do the handshake
            handshaker.handshake(ctx.channel(), lastRequest);

            // And hook up the subscriber/publishers
            response.subscribe(subscriber);
            publisher.subscribe(response);
        }

    }

    @Override
    protected void bodyRequested(ChannelHandlerContext ctx) {
        if (continueExpected) {
            if (inFlight == 1) {
                ctx.writeAndFlush(new DefaultFullHttpResponse(lastRequest.getProtocolVersion(), HttpResponseStatus.CONTINUE));
                continueExpected = false;
            } else {
                sendContinue = true;
            }
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        super.handlerRemoved(ctx);
        for (ChannelHandler dependent: dependentHandlers) {
            try {
                ctx.pipeline().remove(dependent);
            } catch (NoSuchElementException e) {
                // Ignore, maybe something else removed it
            }
        }
    }
}
