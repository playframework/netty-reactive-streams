package com.typesafe.netty;

import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalEventLoopGroup;
import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

abstract class AbstractHandlerPublisherVerification extends PublisherVerification<Long> {

    protected LocalEventLoopGroup eventLoop;

    public AbstractHandlerPublisherVerification() {
        super(new TestEnvironment());
    }

    // I tried making this before/after class, but encountered a strange error where after 32 publishers were created,
    // the following tests complained about the executor being shut down when I registered the channel. Though, it
    // doesn't happen if you create 32 publishers in a single test.
    @BeforeMethod
    public void startEventLoop() {
        eventLoop = new LocalEventLoopGroup();
    }

    @AfterMethod
    public void stopEventLoop() {
        eventLoop.shutdownGracefully();
        eventLoop = null;
    }

    @Override
    public Publisher<Long> createFailedPublisher() {
        HandlerPublisher<Long> publisher = new HandlerPublisher<>(Long.class);
        LocalChannel channel = new LocalChannel();
        eventLoop.register(channel);
        channel.pipeline().addLast("publisher", publisher);
        channel.pipeline().fireExceptionCaught(new RuntimeException("failed"));

        return publisher;
    }
}
