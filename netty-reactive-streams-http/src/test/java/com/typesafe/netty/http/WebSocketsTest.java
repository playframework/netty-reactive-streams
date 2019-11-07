package com.typesafe.netty.http;

import akka.actor.ActorSystem;
import akka.japi.function.Function;
import akka.stream.Materializer;
import akka.stream.javadsl.Flow;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.ReferenceCountUtil;
import org.reactivestreams.Processor;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.*;

public class WebSocketsTest {

    private NioEventLoopGroup eventLoop;
    private ActorSystem actorSystem;
    private Materializer materializer;
    private Channel serverBindChannel;
    private Channel client;
    private BlockingQueue<Object> clientEvents = new LinkedBlockingQueue<>();
    private int port;

    @Test
    public void simpleWebSocket() throws Exception {
        start(new AutoReadHandler() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (msg instanceof HttpRequest) {
                    HttpRequest request = (HttpRequest) msg;
                    ReferenceCountUtil.release(msg);

                    Processor<WebSocketFrame, WebSocketFrame> processor = Flow.<WebSocketFrame>create().map(new Function<WebSocketFrame, WebSocketFrame>() {
                        public WebSocketFrame apply(WebSocketFrame msg) throws Exception {
                            if (msg instanceof TextWebSocketFrame) {
                                TextWebSocketFrame echo = new TextWebSocketFrame("echo " + ((TextWebSocketFrame) msg).text());
                                ReferenceCountUtil.release(msg);
                                return echo;
                            } else if (msg instanceof PingWebSocketFrame) {
                                return new PongWebSocketFrame(msg.content());
                            } else if (msg instanceof CloseWebSocketFrame) {
                                return msg;
                            } else {
                                throw new IllegalArgumentException("Unexpected websocket frame: " + msg);
                            }
                        }
                    }).toProcessor().run(materializer);

                    ctx.writeAndFlush(new DefaultWebSocketHttpResponse(request.protocolVersion(),
                            HttpResponseStatus.valueOf(200), processor,
                            new WebSocketServerHandshakerFactory("ws://127.0.0.1/" + port + "/", null, false)
                    ));
                }
            }
        });

        makeWebSocketRequest();
        assertNoMessages();
        client.writeAndFlush(new TextWebSocketFrame("hello"));
        assertEquals(readTextFrame(), "echo hello");

        ByteBuf ping = Unpooled.wrappedBuffer("hello".getBytes());
        client.writeAndFlush(new PingWebSocketFrame(ping));
        Object pong = pollClient();
        assertNotNull(pong);
        if (pong instanceof PongWebSocketFrame) {
            assertEquals(((PongWebSocketFrame) pong).content().toString(Charset.defaultCharset()), "hello");
        } else {
            fail("Expected pong reply but got " + pong);
        }
        ReferenceCountUtil.release(pong);

        client.writeAndFlush(new CloseWebSocketFrame(1000, "no reason"));
        Object close = pollClient();
        assertNotNull(close);
        if (close instanceof CloseWebSocketFrame) {
            CloseWebSocketFrame cl = (CloseWebSocketFrame) close;
            assertEquals(cl.statusCode(), 1000);
            assertEquals(cl.reasonText(), "no reason");
        } else {
            fail("Expected close but got " + close);
        }
        ReferenceCountUtil.release(close);

        client.close();
        assertNoMessages();
    }

    @Test
    public void rejectWebSocket() throws Exception {
        start(new AutoReadHandler() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (msg instanceof HttpRequest) {
                    HttpRequest request = (HttpRequest) msg;
                    ReferenceCountUtil.release(msg);

                    Processor<WebSocketFrame, WebSocketFrame> processor = Flow.<WebSocketFrame>create().toProcessor().run(materializer);

                    ctx.writeAndFlush(new DefaultWebSocketHttpResponse(request.protocolVersion(),
                            HttpResponseStatus.valueOf(200), processor,
                            new WebSocketServerHandshakerFactory("ws://127.0.0.1/" + port + "/", null, false)
                    ));
                }
            }
        });

        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        HttpHeaders headers = request.headers();
        headers.add(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET.toLowerCase())
                .add(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE)
                .add(HttpHeaderNames.SEC_WEBSOCKET_KEY, "foobar")
                .add(HttpHeaderNames.HOST, "http://127.0.0.1:" + port)
                .add(HttpHeaderNames.SEC_WEBSOCKET_ORIGIN, "http://127.0.0.1:" + port)
                .add(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "1");
        client.writeAndFlush(request);

        FullHttpResponse response = receiveFullResponse();
        assertEquals(response.status(), HttpResponseStatus.UPGRADE_REQUIRED);
        assertEquals(response.headers().get(HttpHeaderNames.SEC_WEBSOCKET_VERSION), "13");
        ReferenceCountUtil.release(response);
    }

    @BeforeClass
    public void startEventLoop() {
        eventLoop = new NioEventLoopGroup();
        actorSystem = ActorSystem.create();
        materializer = Materializer.matFromSystem(actorSystem);
    }

    @AfterClass
    public void stopEventLoop() {
        actorSystem.terminate();
        eventLoop.shutdownGracefully();
    }

    @AfterMethod
    public void closeChannels() throws InterruptedException {
        if (serverBindChannel != null) {
            serverBindChannel.close();
        }
        if (client != null) {
            client.close();
        }
        clientEvents = null;
    }

    private void start(final ChannelHandler handler) throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(eventLoop)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.AUTO_READ, false)
                .localAddress("127.0.0.1", 0)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();

                        pipeline.addLast(
                                new HttpRequestDecoder(),
                                new HttpResponseEncoder()
                        ).addLast("serverStreamsHandler", new HttpStreamsServerHandler())
                                .addLast(handler);
                    }
                });

        serverBindChannel = bootstrap.bind().await().channel();
        port = ((InetSocketAddress) serverBindChannel.localAddress()).getPort();

        clientEvents = new LinkedBlockingQueue<>();
        Bootstrap client = new Bootstrap()
                .group(eventLoop)
                .option(ChannelOption.AUTO_READ, false)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        final ChannelPipeline pipeline = ch.pipeline();

                        pipeline.addLast(new HttpClientCodec(), new HttpObjectAggregator(8192))
                                .addLast(new AutoReadHandler() {
                                    // Store a reference to the current client events
                                    BlockingQueue<Object> events = clientEvents;
                                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                        events.add(msg);
                                    }
                                });
                    }
                });

        this.client = client.remoteAddress(serverBindChannel.localAddress()).connect().await().channel();
    }

    private void makeWebSocketRequest() throws InterruptedException {
        WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                URI.create("ws://127.0.0.1:" + port + "/"),
                WebSocketVersion.V13, null, false, new DefaultHttpHeaders());
        handshaker.handshake(client);
        FullHttpResponse response = receiveFullResponse();
        handshaker.finishHandshake(client, response);
    }

    private FullHttpResponse receiveFullResponse() throws InterruptedException {
        Object msg = pollClient();
        assertNotNull(msg);
        if (msg instanceof FullHttpResponse) {
            return (FullHttpResponse) msg;
        } else {
            throw new AssertionError("Expected FullHttpResponse, got " + msg);
        }
    }

    private String readTextFrame() throws InterruptedException {
        Object msg = pollClient();
        assertNotNull(msg);
        if (msg instanceof TextWebSocketFrame) {
            String text = ((TextWebSocketFrame) msg).text();
            ReferenceCountUtil.release(msg);
            return text;
        } else {
            throw new AssertionError("Expected text web socket frame, got " + msg);
        }
    }

    private void assertNoMessages() throws InterruptedException {
        assertNull(pollClient());
    }

    private Object pollClient() throws InterruptedException {
        return clientEvents.poll(500, TimeUnit.MILLISECONDS);
    }

    private class AutoReadHandler extends ChannelInboundHandlerAdapter {
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            ctx.read();
        }

        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
            ctx.read();
        }
    }

}
