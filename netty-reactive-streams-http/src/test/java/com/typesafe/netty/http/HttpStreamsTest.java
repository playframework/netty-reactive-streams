package com.typesafe.netty.http;

import akka.actor.ActorSystem;
import akka.japi.function.Function;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Flow;
import com.typesafe.netty.HandlerPublisher;
import com.typesafe.netty.HandlerSubscriber;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import org.reactivestreams.Processor;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.*;

public class HttpStreamsTest {

    private NioEventLoopGroup eventLoop;
    private ActorSystem actorSystem;
    private Materializer materializer;
    private HttpHelper helper;
    private Channel serverBindChannel;
    private Channel client;
    private final BlockingQueue<Object> clientEvents = new LinkedBlockingQueue<>();

    private static final long TIMEOUT_MS = 5000;

    @Test
    public void streamedRequestResponse() throws Exception {
        createEchoServer();
        client.writeAndFlush(helper.createStreamedRequest("POST", "/", Arrays.asList("hello", " ", "world"), 11));
        StreamedHttpResponse response = receiveStreamedResponse();

        helper.assertRequestTypeStreamed(response);
        assertEquals(helper.getRequestContentLength(response), 11);

        assertEquals(helper.extractBody(response), "hello world");
        assertEquals(HttpUtil.getContentLength(response), 11);
    }

    @Test
    public void chunkedRequestResponse() throws Exception {
        createEchoServer();
        client.writeAndFlush(helper.createChunkedRequest("POST", "/", Arrays.asList("hello", " ", "world")));
        StreamedHttpResponse response = receiveStreamedResponse();

        helper.assertRequestTypeStreamed(response);
        assertFalse(helper.hasRequestContentLength(response));

        assertEquals(helper.extractBody(response), "hello world");
        assertTrue(HttpUtil.isTransferEncodingChunked(response));
    }

    @Test
    public void emptyRequestResponse() throws Exception {
        createEchoServer();
        client.writeAndFlush(helper.createStreamedRequest("POST", "/", Collections.<String>emptyList()));
        FullHttpResponse response = receiveFullResponse();

        helper.assertRequestTypeFull(response);
        assertFalse(helper.hasRequestContentLength(response));

        assertEquals(helper.extractBody(response), "");
        assertEquals(HttpUtil.getContentLength(response), 0);
    }

    @Test
    public void zeroLengthRequestResponse() throws Exception {
        createEchoServer();
        client.writeAndFlush(helper.createStreamedRequest("POST", "/", Collections.<String>emptyList(), 0));
        FullHttpResponse response = receiveFullResponse();

        helper.assertRequestTypeFull(response);
        assertEquals(helper.getRequestContentLength(response), 0);

        assertEquals(helper.extractBody(response), "");
        assertEquals(HttpUtil.getContentLength(response), 0);
    }

    @Test
    public void noContentLengthResponse() throws Exception {
        responseServer(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.copiedBuffer("hello world", Charset.forName("utf-8"))));
        client.writeAndFlush(helper.createStreamedRequest("GET", "/", Collections.<String>emptyList()));
        StreamedHttpResponse response = receiveStreamedResponse();

        assertFalse(HttpUtil.isContentLengthSet(response));
        assertEquals(helper.extractBody(response), "hello world");

        client.closeFuture().await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertFalse(client.isOpen());
    }

    @Test
    public void noContentLength204Response() throws Exception {
        responseServer(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT,
                Unpooled.EMPTY_BUFFER));
        client.writeAndFlush(helper.createStreamedRequest("GET", "/", Collections.<String>emptyList()));
        FullHttpResponse response = receiveFullResponse();

        assertFalse(HttpUtil.isContentLengthSet(response));
        assertEquals(helper.extractBody(response), "");

        client.closeFuture().await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertTrue(client.isOpen());
    }

    @Test
    public void cancelRequestBody() throws Exception {
        createEchoServer();
        start(new AutoReadHandler() {
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (msg instanceof StreamedHttpRequest) {
                    StreamedHttpRequest request = (StreamedHttpRequest) msg;
                    if (request.uri().equals("/cancel")) {
                        helper.cancelStreamedMessage(request);
                        HttpResponse response = helper.createFullResponse("");
                        response.headers().set("Cancelled", true);
                        ctx.writeAndFlush(response);
                    } else {
                        ctx.writeAndFlush(helper.echo(msg));
                    }
                } else {
                    ctx.writeAndFlush(helper.echo(msg));
                }
                ctx.read();
            }
        });

        client.writeAndFlush(helper.createStreamedRequest("POST", "/cancel", Arrays.asList("hello", " ", "world"), 11));
        FullHttpResponse response = receiveFullResponse();

        assertEquals(response.headers().get("Cancelled"), "true");
        assertEquals(helper.extractBody(response), "");

        // Ensure that the connection is still usable
        client.writeAndFlush(helper.createStreamedRequest("POST", "/", Arrays.asList("Hello", " ", "World"), 11));
        StreamedHttpResponse response2 = receiveStreamedResponse();
        assertEquals(helper.extractBody(response2), "Hello World");
    }

    @Test
    public void cancelResponseBody() throws Exception {
        createEchoServer();
        client.writeAndFlush(helper.createStreamedRequest("POST", "/", Arrays.asList("hello", " ", "world"), 11));
        StreamedHttpResponse response = receiveStreamedResponse();
        helper.cancelStreamedMessage(response);

        // Ensure that the connection is still usable
        client.writeAndFlush(helper.createStreamedRequest("POST", "/", Arrays.asList("Hello", " ", "World"), 11));
        StreamedHttpResponse response2 = receiveStreamedResponse();
        assertEquals(helper.extractBody(response2), "Hello World");
    }

    @Test
    public void expect100ContinueAccepted() throws Exception {
        start(new AutoReadHandler() {
            @Override
            public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
                helper.extractBodyAsync(msg).thenApply(new java.util.function.Function<String, Object>() {
                    @Override
                    public Object apply(String body) {
                        ctx.writeAndFlush(helper.createFullResponse(body));
                        return null;
                    }
                });
            }
        });
        StreamedHttpRequest request = helper.createStreamedRequest("POST", "/", Arrays.asList("hello", " ", "world"), 11);
        HttpUtil.set100ContinueExpected(request, true);
        client.writeAndFlush(request);

        StreamedHttpResponse response = receiveStreamedResponse();
        assertEquals(helper.extractBody(response), "hello world");

        // Ensure that the connection is still usable
        client.writeAndFlush(helper.createStreamedRequest("POST", "/", Arrays.asList("Hello", " ", "World"), 11));
        StreamedHttpResponse response2 = receiveStreamedResponse();
        assertEquals(helper.extractBody(response2), "Hello World");
    }

    @Test
    public void expect100ContinueRejected() throws Exception {
        start(new AutoReadHandler() {
            @Override
            public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
                ctx.writeAndFlush(helper.createFullResponse("rejected"));
            }
        });
        StreamedHttpRequest request = helper.createStreamedRequest("POST", "/", Arrays.asList("hello", " ", "world"), 11);
        HttpUtil.set100ContinueExpected(request, true);
        client.writeAndFlush(request);

        StreamedHttpResponse response = receiveStreamedResponse();
        assertEquals(helper.extractBody(response), "rejected");

        // Ensure that the connection was closed
        client.closeFuture().await();
        assertFalse(client.isOpen());
    }

    @Test
    public void expect100ContinuePipelining() throws Exception {
        start(new ChannelHandlerAdapter() {
            public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
                HandlerPublisher<HttpRequest> publisher = new HandlerPublisher<>(ctx.executor(), HttpRequest.class);
                HandlerSubscriber<HttpResponse> subscriber = new HandlerSubscriber<>(ctx.executor());
                ctx.pipeline().addLast(publisher, subscriber);
                Processor<HttpRequest, HttpResponse> processor = AkkaStreamsUtil.flowToProcessor(Flow.<HttpRequest>create()
                        .mapAsync(4, new Function<HttpRequest, CompletionStage<String>>() {
                            public CompletionStage<String> apply(HttpRequest request) throws Exception {
                                return helper.extractBodyAsync(request);
                            }
                        }).map(new Function<String, HttpResponse>() {
                            public HttpResponse apply(String body) throws Exception {
                                HttpResponse response = helper.createFullResponse(body);
                                response.headers().set("Body", body);
                                return response;
                            }
                        }),
                    materializer
                );
                publisher.subscribe(processor);
                processor.subscribe(subscriber);
            }
        });
        client.writeAndFlush(helper.createStreamedRequest("POST", "/", Collections.singletonList("request 1"), 9));
        client.writeAndFlush(helper.createStreamedRequest("POST", "/", Collections.singletonList("request 2"), 9));
        StreamedHttpRequest request3 = helper.createStreamedRequest("POST", "/", Collections.singletonList("request 3"), 9);
        HttpUtil.set100ContinueExpected(request3, true);
        client.writeAndFlush(request3);
        client.writeAndFlush(helper.createStreamedRequest("POST", "/", Collections.singletonList("request 4"), 9));
        client.writeAndFlush(helper.createStreamedRequest("POST", "/", Collections.singletonList("request 5"), 9));
        StreamedHttpRequest request6 = helper.createStreamedRequest("POST", "/", Collections.singletonList("request 6"), 9);
        HttpUtil.set100ContinueExpected(request6, true);
        client.writeAndFlush(request6);

        assertEquals(helper.extractBody(receiveStreamedResponse()), "request 1");
        assertEquals(helper.extractBody(receiveStreamedResponse()), "request 2");
        assertEquals(helper.extractBody(receiveStreamedResponse()), "request 3");
        assertEquals(helper.extractBody(receiveStreamedResponse()), "request 4");
        assertEquals(helper.extractBody(receiveStreamedResponse()), "request 5");
        assertEquals(helper.extractBody(receiveStreamedResponse()), "request 6");
    }

    @Test
    public void closeAHttp11ConnectionWhenRequestedByFullResponse() throws Exception {
        start(new AutoReadHandler() {
            @Override
            public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
                HttpResponse response = helper.createFullResponse("");
                response.headers().set(HttpHeaderNames.CONNECTION, "close");
                ctx.writeAndFlush(response);
            }
        });
        client.writeAndFlush(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));

        FullHttpResponse response = receiveFullResponse();
        assertEquals(helper.extractBody(response), "");

        client.closeFuture().await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertFalse(client.isOpen());
    }

    @Test
    public void closeAHttp11ConnectionWhenRequestedByStreamedResponse() throws Exception {
        start(new AutoReadHandler() {
            @Override
            public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
                HttpResponse response = helper.createStreamedResponse(HttpVersion.HTTP_1_1, Arrays.asList("hello", " ", "world"), 11);
                response.headers().set(HttpHeaderNames.CONNECTION, "close");
                ctx.writeAndFlush(response);
            }
        });
        client.writeAndFlush(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));

        StreamedHttpResponse response = receiveStreamedResponse();
        assertEquals(helper.extractBody(response), "hello world");

        client.closeFuture().await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertFalse(client.isOpen());
    }

    @Test
    public void closeAHttp10ConnectionWhenRequestedByFullResponse() throws Exception {
        start(new AutoReadHandler() {
            @Override
            public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
                HttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_0, HttpResponseStatus.OK);
                HttpUtil.setContentLength(response, 0);
                ctx.writeAndFlush(response);
            }
        });
        client.writeAndFlush(new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/"));

        FullHttpResponse response = receiveFullResponse();
        assertEquals(helper.extractBody(response), "");

        client.closeFuture().await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertFalse(client.isOpen());
    }

    @Test
    public void closeAHttp10ConnectionWhenRequestedByStreamedResponse() throws Exception {
        start(new AutoReadHandler() {
            @Override
            public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
                HttpResponse response = helper.createStreamedResponse(HttpVersion.HTTP_1_0, Arrays.asList("hello", " ", "world"), 11);
                ctx.writeAndFlush(response);
            }
        });
        client.writeAndFlush(new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/"));

        StreamedHttpResponse response = receiveStreamedResponse();
        assertEquals(helper.extractBody(response), "hello world");

        client.closeFuture().await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertFalse(client.isOpen());
    }

    @BeforeClass
    public void startEventLoop() {
        eventLoop = new NioEventLoopGroup();
        actorSystem = ActorSystem.create();
        materializer = ActorMaterializer.create(actorSystem);
        helper = new HttpHelper(materializer);
    }

    @AfterClass
    public void stopEventLoop() throws Exception {
        clientEvents.clear();
        Await.ready(actorSystem.terminate(), Duration.create(10000, TimeUnit.MILLISECONDS));
        eventLoop.shutdownGracefully(100, 10000, TimeUnit.MILLISECONDS).await();
    }

    @AfterMethod
    public void closeChannels() {
        if (serverBindChannel != null) {
            serverBindChannel.close();
        }
        if (client != null) {
            client.close();
        }
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

        Bootstrap client = new Bootstrap()
                .group(eventLoop)
                .option(ChannelOption.AUTO_READ, false)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        final ChannelPipeline pipeline = ch.pipeline();

                        pipeline.addLast(new HttpClientCodec())
                                .addLast("clientStreamsHandler", new HttpStreamsClientHandler())
                                .addLast(new AutoReadHandler() {
                                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                        clientEvents.add(msg);
                                    }
                                });
                    }
                });

        this.client = client.remoteAddress(serverBindChannel.localAddress()).connect().await().channel();
    }

    private StreamedHttpResponse receiveStreamedResponse() throws InterruptedException {
        Object msg = clientEvents.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull(msg, "Read response timed out");
        if (msg instanceof StreamedHttpResponse) {
            return (StreamedHttpResponse) msg;
        } else {
            throw new AssertionError("Expected StreamedHttpResponse, got " + msg);
        }
    }

    private FullHttpResponse receiveFullResponse() throws InterruptedException {
        Object msg = clientEvents.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull(msg);
        if (msg instanceof FullHttpResponse) {
            return (FullHttpResponse) msg;
        } else {
            throw new AssertionError("Expected FullHttpResponse, got " + msg);
        }
    }

    private void createEchoServer() throws InterruptedException {
        start(new AutoReadHandler() {
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                ctx.writeAndFlush(helper.echo(msg));
            }
        });
    }

    private void responseServer(final HttpResponse response) throws InterruptedException {
        start(new AutoReadHandler() {
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (msg instanceof HttpRequest) {
                    ctx.writeAndFlush(response);
                } else {
                    throw new IllegalArgumentException("Unknown incoming message type: " + msg);
                }
            }
        });
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
