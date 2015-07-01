package com.typesafe.netty.http;

import akka.actor.ActorSystem;
import akka.dispatch.Futures;
import akka.japi.function.Function;
import akka.japi.function.Function2;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ResourceLeakDetector;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.tck.IdentityProcessorVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.*;
import scala.concurrent.Future;
import scala.runtime.BoxedUnit;

import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * This identity processor verification verifies a client making requests to a server, that echos the
 * body back.
 *
 * The server uses the {@link HttpStreamsServerHandler}, and then exposes the messages sent/received by
 * that using reactive streams.  So it effectively uses streams of streams.  It then uses Akka streams
 * to actually handle the requests, echoing the bodies back in the responses as is.
 *
 * The client uses the {@link HttpStreamsClientHandler}, and then exposes the messages sent/received by
 * that using reactive streams, so it too is effectively a stream of streams.  Here Akka streams is used
 * to split the String bodies into many chunks, for more interesting verification of the bodies, and then
 * combines all the chunks together back into a String at the end.
 */
public class FullStackHttpIdentityProcessorVerificationTest extends IdentityProcessorVerification<String> {

    private NioEventLoopGroup eventLoop;
    private Channel serverBindChannel;
    private ActorSystem actorSystem;
    private Materializer materializer;
    private ExecutorService executorService;

    public FullStackHttpIdentityProcessorVerificationTest() {
        super(new TestEnvironment(500));
    }

    @BeforeClass
    public void startActorSystem() {
        executorService = Executors.newCachedThreadPool();
        actorSystem = ActorSystem.create();
        materializer = ActorMaterializer.create(actorSystem);
    }

    @AfterClass
    public void stopActorSystem() {
        executorService.shutdown();
        actorSystem.shutdown();
    }

    @BeforeClass
    public void startServer() throws Exception {
        eventLoop = new NioEventLoopGroup();
        ProcessorHttpServer server = new ProcessorHttpServer(eventLoop);

        // A flow that echos HttpRequest bodies in HttpResponse bodies
        final Flow<HttpRequest, HttpResponse, BoxedUnit> flow = Flow.<HttpRequest>create().map(
                new Function<HttpRequest, HttpResponse>() {
                    public HttpResponse apply(HttpRequest request) throws Exception {
                        HttpResponse response;
                        if (request instanceof StreamedHttpRequest) {
                            response = new DefaultStreamedHttpResponse(request.getProtocolVersion(),
                                    HttpResponseStatus.OK, (StreamedHttpRequest) request);
                        } else if (request instanceof FullHttpRequest) {
                            response = new DefaultFullHttpResponse(request.getProtocolVersion(),
                                    HttpResponseStatus.OK, ((FullHttpRequest) request).content());
                        } else {
                            throw new IllegalArgumentException("Unsupported http message type: " + request);
                        }
                        if (HttpHeaders.isTransferEncodingChunked(request)) {
                            HttpHeaders.setTransferEncodingChunked(response);
                        } else {
                            HttpHeaders.setContentLength(response, HttpHeaders.getContentLength(request, 0));
                        }
                        HttpHeaders.setHeader(response, "Location", request.getUri());
                        return response;
                    }
                }
        );

        serverBindChannel = server.bind(new InetSocketAddress("127.0.0.1", 0), new Callable<Processor<HttpRequest, HttpResponse>>() {
            @Override
            public Processor<HttpRequest, HttpResponse> call() throws Exception {
                return AkkaStreamsUtil.flowToProcessor(flow, materializer);
            }
        }).await().channel();
    }

    @AfterClass
    public void stopServer() throws Exception {
        serverBindChannel.close().await();
        eventLoop.shutdownGracefully();
    }

    @Override
    public Processor<String, String> createIdentityProcessor(int bufferSize) {

        ProcessorHttpClient client = new ProcessorHttpClient(eventLoop);

        Processor<HttpRequest, HttpResponse> connection = client.connect(serverBindChannel.localAddress());

        Flow<String, String, ?> flow = Flow.<String>create()
                // Convert the Strings to HttpRequests
                .map(new Function<String, HttpRequest>() {
                    @Override
                    public HttpRequest apply(String body) throws Exception {
                        List<HttpContent> content = new ArrayList<>();
                        String[] chunks = body.split(":");
                        for (String chunk: chunks) {
                            // Make sure we put the ":" back into the body
                            String c;
                            if (content.isEmpty()) {
                                c = chunk;
                            } else {
                                c = ":" + chunk;
                            }
                            content.add(new DefaultHttpContent(Unpooled.copiedBuffer(c, Charset.forName("utf-8"))));
                        }
                        Publisher<HttpContent> publisher = Source.from(content).runWith(Sink.<HttpContent>publisher(), materializer);
                        HttpRequest request = new DefaultStreamedHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/" + chunks[0],
                                publisher);
                        HttpHeaders.setTransferEncodingChunked(request);
                        return request;
                    }
                })
                // Send the flow via the HTTP client connection
                .via(AkkaStreamsUtil.processorToFlow(connection))
                // Convert the responses to Strings
                .mapAsync(4, new Function<HttpResponse, Future<String>>() {
                    @Override
                    public Future<String> apply(HttpResponse response) throws Exception {
                        if (response instanceof FullHttpResponse) {
                            String body = contentAsString((FullHttpResponse) response);
                            return Futures.successful(body);
                        } else if (response instanceof StreamedHttpResponse) {

                            return Source.from((StreamedHttpResponse) response).runFold("", new Function2<String, HttpContent, String>() {
                                @Override
                                public String apply(String body, HttpContent content) throws Exception {
                                    return body + contentAsString(content);
                                }
                            }, materializer);
                        } else {
                            throw new IllegalArgumentException("Unknown response type: " + response);
                        }
                    }
                });

        return AkkaStreamsUtil.flowToProcessor(flow, materializer);
    }

    private String contentAsString(HttpContent content) {
        String body = content.content().toString(Charset.forName("utf-8"));
        ReferenceCountUtil.release(content);
        return body;
    }

    @Override
    public Publisher<String> createFailedPublisher() {
        return Source.<String>failed(new RuntimeException("failed"))
                .toMat(Sink.<String>publisher(), Keep.<BoxedUnit, Publisher<String>>right()).run(materializer);
    }

    @Override
    public ExecutorService publisherExecutorService() {
        return executorService;
    }

    /**
     * We want to send a list of chunks, but the problem is, Netty may (and does) dechunk things in certain
     * circumstances.  So, we create Strings, and we split it into chunks it in the flow, then combine all
     * chunks when it gets back, then split again, that way if netty combines the chunks, it doesn't matter.
     */
    @Override
    public String createElement(int element) {
        StringBuilder sb = new StringBuilder();
        // Make the first element the number, then we set the URL to that number when we make the request,
        // making debugging easier.
        sb.append(element);
        for (int i = 0; i < 20; i++) {
            sb.append(":this is a very cool element, it is element number ").append(i);
        }
        return sb.toString();
    }

    @Override
    public long maxSupportedSubscribers() {
        return 1;
    }
}
