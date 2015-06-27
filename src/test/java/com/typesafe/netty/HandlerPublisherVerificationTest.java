package com.typesafe.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalEventLoopGroup;
import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

import java.util.concurrent.atomic.AtomicLong;


public class HandlerPublisherVerificationTest extends PublisherVerification<Long> {

    private LocalEventLoopGroup eventLoop;
    private final AtomicLong test = new AtomicLong();

    public HandlerPublisherVerificationTest() {
        super(new TestEnvironment(100));
    }

    @BeforeClass
    public void startEventLoop() {
        eventLoop = new LocalEventLoopGroup();
    }

    @AfterClass
    public void stopEventLoop() {
        eventLoop.shutdownGracefully();
        eventLoop = null;
    }

    @Override
    public Publisher<Long> createPublisher(final long elements) {
        final long publisherId = test.incrementAndGet();
        HandlerPublisher<Long> publisher = new HandlerPublisher<>(createMessageHandler(elements));

        final AtomicLong counter = new AtomicLong();
        LocalChannel channel = new LocalChannel();
        ChannelOutboundHandler out = new ChannelOutboundHandlerAdapter() {
            @Override
            public void read(final ChannelHandlerContext ctx) throws Exception {
                ctx.executor().execute(new Runnable() {
                    public void run() {
                        ctx.fireChannelRead(counter.getAndIncrement());
                        ctx.fireChannelRead(counter.getAndIncrement());
                        ctx.fireChannelReadComplete();
                    }
                });
            }
        };
        eventLoop.register(channel);
        channel.pipeline().addLast("out", out);
        channel.pipeline().addLast("publisher", publisher);

        return publisher;
    }

    @Override
    public Publisher<Long> createFailedPublisher() {
        HandlerPublisher<Long> publisher = new HandlerPublisher<>(createMessageHandler(0));
        LocalChannel channel = new LocalChannel();
        eventLoop.register(channel);
        channel.pipeline().addLast("publisher", publisher);
        channel.pipeline().fireExceptionCaught(new RuntimeException("failed"));

        return publisher;
    }

    private PublisherMessageHandler<Long> createMessageHandler(final long elements) {
        return new PublisherMessageHandler<Long>() {
            public SubscriberEvent<Long> transform(Object message, ChannelHandlerContext ctx) {
                long item = (Long) message;
                if (item == elements && elements != Long.MAX_VALUE) {
                    return Complete.instance();
                } else {
                    return new Next<>(item);
                }
            }
            public void messageDropped(Object message, ChannelHandlerContext ctx) {
            }
            public void cancel(ChannelHandlerContext ctx) {
            }
        };
    }
}
