package com.typesafe.netty.http;

import com.typesafe.netty.HandlerPublisher;
import com.typesafe.netty.HandlerSubscriber;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.concurrent.*;
import java.util.concurrent.Flow.Processor;

import java.net.SocketAddress;

public class ProcessorHttpClient {

    private final EventLoopGroup eventLoop;

    public ProcessorHttpClient(EventLoopGroup eventLoop) {
        this.eventLoop = eventLoop;
    }

    public Processor<HttpRequest, HttpResponse> connect(SocketAddress address) {

        final EventExecutor executor = eventLoop.next();
        final Promise<ChannelPipeline> promise = new DefaultPromise<>(executor);
        final HandlerPublisher<HttpResponse> publisherHandler = new HandlerPublisher<>(executor,
                HttpResponse.class);
        final HandlerSubscriber<HttpRequest> subscriberHandler = new HandlerSubscriber<HttpRequest>(
                executor, 2, 4) {
            @Override
            protected void error(final Throwable error) {

                promise.addListener(new GenericFutureListener<Future<ChannelPipeline>>() {
                    @Override
                    public void operationComplete(Future<ChannelPipeline> future) throws Exception {
                        ChannelPipeline pipeline = future.getNow();
                        pipeline.fireExceptionCaught(error);
                        pipeline.close();
                    }
                });
            }
        };

        Bootstrap client = new Bootstrap()
                .group(eventLoop)
                .option(ChannelOption.AUTO_READ, false)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {

                        final ChannelPipeline pipeline = ch.pipeline();


                        pipeline
                                .addLast(new HttpClientCodec())
                                .addLast("clientStreamsHandler", new HttpStreamsClientHandler())
                                .addLast(executor, "clientPublisher", publisherHandler)
                                .addLast(executor, "clientSubscriber", subscriberHandler);

                        promise.setSuccess(pipeline);
                    }
                });

        client.remoteAddress(address).connect();
        return new DelegateProcessor<>(subscriberHandler, publisherHandler);
    }
}
