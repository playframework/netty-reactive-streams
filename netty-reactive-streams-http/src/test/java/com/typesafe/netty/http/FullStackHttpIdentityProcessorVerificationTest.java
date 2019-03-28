package com.typesafe.netty.http;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.japi.function.Function;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.*;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.*;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.tck.IdentityProcessorVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.*;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    private HttpHelper helper;
    private ExecutorService executorService;

    public FullStackHttpIdentityProcessorVerificationTest() {
        super(new TestEnvironment(1000));
    }

    @BeforeClass
    public void start() throws Exception {
        executorService = Executors.newCachedThreadPool();
        actorSystem = ActorSystem.create();
        materializer = ActorMaterializer.create(actorSystem);
        helper = new HttpHelper(materializer);
        eventLoop = new NioEventLoopGroup();
        ProcessorHttpServer server = new ProcessorHttpServer(eventLoop);

        // A flow that echos HttpRequest bodies in HttpResponse bodies
        final Flow<HttpRequest, HttpResponse, NotUsed> flow = Flow.<HttpRequest>create().map(
                new Function<HttpRequest, HttpResponse>() {
                    public HttpResponse apply(HttpRequest request) throws Exception {
                        return helper.echo(request);
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
    public void stop() throws Exception {
        serverBindChannel.close().await();
        executorService.shutdown();
        Await.ready(actorSystem.terminate(), Duration.create(10000, TimeUnit.MILLISECONDS));
        eventLoop.shutdownGracefully(100, 10000, TimeUnit.MILLISECONDS).await();
    }

    @Override
    public Processor<String, String> createIdentityProcessor(int bufferSize) {

        ProcessorHttpClient client = new ProcessorHttpClient(eventLoop);
        Processor<HttpRequest, HttpResponse> connection = getProcessor(client);

        Flow<String, String, ?> flow = Flow.<String>create()
                // Convert the Strings to HttpRequests
                .map(new Function<String, HttpRequest>() {
                    @Override
                    public HttpRequest apply(String body) throws Exception {
                        List<String> content = new ArrayList<>();
                        String[] chunks = body.split(":");
                        for (String chunk: chunks) {
                            // Make sure we put the ":" back into the body
                            String c;
                            if (content.isEmpty()) {
                                c = chunk;
                            } else {
                                c = ":" + chunk;
                            }
                            content.add(c);
                        }
                        return helper.createChunkedRequest("POST", "/" + chunks[0], content);
                    }
                })
                // Send the flow via the HTTP client connection
                .via(AkkaStreamsUtil.processorToFlow(connection))
                // Convert the responses to Strings
                .mapAsync(4, new Function<HttpResponse, CompletionStage<String>>() {
                    @Override
                    public CompletionStage<String> apply(HttpResponse response) throws Exception {
                        return helper.extractBodyAsync(response);
                    }
                });

        return AkkaStreamsUtil.flowToProcessor(flow, materializer);
    }

    private Processor<HttpRequest, HttpResponse> getProcessor(ProcessorHttpClient client) {
        try {
            return client.connect(serverBindChannel.localAddress());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public Publisher<String> createFailedPublisher() {
        return Source.<String>failed(new RuntimeException("failed"))
                .toMat(Sink.<String>asPublisher(AsPublisher.WITH_FANOUT), Keep.<NotUsed, Publisher<String>>right()).run(materializer);
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
