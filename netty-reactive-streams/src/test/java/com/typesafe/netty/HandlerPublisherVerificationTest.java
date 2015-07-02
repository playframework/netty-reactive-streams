package com.typesafe.netty;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalEventLoopGroup;
import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class HandlerPublisherVerificationTest extends PublisherVerification<Long> {

    private final int batchSize;
    // The number of elements to publish initially, before the subscriber is received
    private final int publishInitial;
    // Whether we should use scheduled publishing (with a small delay)
    private final boolean scheduled;

    private ScheduledExecutorService executor;
    private LocalEventLoopGroup eventLoop;

    // For debugging, change the data provider to simple, and adjust the parameters below
    @Factory(dataProvider = "noScheduled")
    public HandlerPublisherVerificationTest(int batchSize, int publishInitial, boolean scheduled) {
        super(new TestEnvironment(200));
        this.batchSize = batchSize;
        this.publishInitial = publishInitial;
        this.scheduled = scheduled;
    }

    @DataProvider
    public static Object[][] simple() {
        boolean scheduled = false;
        int batchSize = 2;
        int publishInitial = 0;
        return new Object[][] {
                new Object[] {batchSize, publishInitial, scheduled}
        };
    }

    @DataProvider
    public static Object[][] full() {
        List<Object[]> data = new ArrayList<>();
        for (Boolean scheduled : Arrays.asList(false, true)) {
            for (int batchSize : Arrays.asList(1, 3)) {
                for (int publishInitial : Arrays.asList(0, 3)) {
                    data.add(new Object[]{batchSize, publishInitial, scheduled});
                }
            }
        }
        return data.toArray(new Object[][]{});
    }

    @DataProvider
    public static Object[][] noScheduled() {
        List<Object[]> data = new ArrayList<>();
        for (int batchSize : Arrays.asList(1, 3)) {
            for (int publishInitial : Arrays.asList(0, 3)) {
                data.add(new Object[]{batchSize, publishInitial, false});
            }
        }
        return data.toArray(new Object[][]{});
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

    @BeforeClass
    public void startExecutor() {
        if (scheduled) {
            executor = Executors.newSingleThreadScheduledExecutor();
        }
    }

    @AfterClass
    public void stopExecutor() {
        if (scheduled) {
            executor.shutdown();
        }
    }

    @Override
    public Publisher<Long> createPublisher(final long elements) {
        final HandlerPublisher<Long> publisher = new HandlerPublisher<>(Long.class);

        final BatchedProducer out;
        if (scheduled) {
            out = new ScheduledBatchedProducer(elements, batchSize, publishInitial, executor, 5);
        } else {
            out = new BatchedProducer(elements, batchSize, publishInitial);
        }

        final ClosedChannel channel = new ClosedChannel();
        channel.config().setAutoRead(false);
        eventLoop.register(channel).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                channel.pipeline().addLast("out", out);
                channel.pipeline().addLast("publisher", publisher);

                for (long i = 0; i < publishInitial && i < elements; i++) {
                    channel.pipeline().fireChannelRead(i);
                }
                if (elements <= publishInitial) {
                    channel.pipeline().fireChannelInactive();
                }
            }
        });

        return publisher;
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

    @Override
    public void stochastic_spec103_mustSignalOnMethodsSequentially() throws Throwable {
        try {
            super.stochastic_spec103_mustSignalOnMethodsSequentially();
        } catch (Throwable t) {
            // CI is failing here, but maven doesn't tell us which parameters failed
            System.out.println("Stochastic test failed with parameters batchSize=" + batchSize +
                    " publishInitial=" + publishInitial + " scheduled=" + scheduled);
            throw t;
        }
    }
}
