package com.typesafe.netty.http;

import com.typesafe.netty.HandlerPublisher;
import com.typesafe.netty.HandlerSubscriber;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;

import java.net.SocketAddress;
import java.util.concurrent.Callable;
import java.util.concurrent.Flow.Processor;

public class ProcessorHttpServer {

    private final EventLoopGroup eventLoop;

    public ProcessorHttpServer(EventLoopGroup eventLoop) {
        this.eventLoop = eventLoop;
    }

    public ChannelFuture bind(SocketAddress address, final Callable<Processor<HttpRequest, HttpResponse>> handler) {
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(eventLoop)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.AUTO_READ, false)
                .localAddress(address)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();

                        pipeline.addLast(
                                new HttpRequestDecoder(),
                                new HttpResponseEncoder()
                        ).addLast("serverStreamsHandler", new HttpStreamsServerHandler());

                        HandlerSubscriber<HttpResponse> subscriber = new HandlerSubscriber<>(ch.eventLoop(), 2, 4);
                        HandlerPublisher<HttpRequest> publisher = new HandlerPublisher<>(ch.eventLoop(), HttpRequest.class);

                        pipeline.addLast("serverSubscriber", subscriber);
                        pipeline.addLast("serverPublisher", publisher);

                        Processor<HttpRequest, HttpResponse> processor = handler.call();
                        processor.subscribe(subscriber);
                        publisher.subscribe(processor);
                    }
                });

        return bootstrap.bind();
    }
}
