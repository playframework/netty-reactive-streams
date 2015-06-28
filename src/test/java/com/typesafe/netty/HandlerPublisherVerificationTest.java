package com.typesafe.netty;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.local.LocalChannel;
import org.reactivestreams.Publisher;
import org.testng.annotations.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class HandlerPublisherVerificationTest extends AbstractHandlerPublisherVerification {

    private final int batchSize;
    // The number of elements to publish initially, before the subscriber is received
    private final int publishInitial;
    // Whether we should use scheduled publishing (with a small delay)
    private final boolean scheduled;

    private ScheduledExecutorService executor;

    // For debugging, change the data provider to simple, and adjust the parameters below
    @Factory(dataProvider = "full")
    public HandlerPublisherVerificationTest(int batchSize, int publishInitial, boolean scheduled) {
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
        for (Boolean removeHandler : Arrays.asList(true, false)) {
            for (int batchSize : Arrays.asList(1, 3)) {
                for (int publishInitial : Arrays.asList(0, 3)) {
                    data.add(new Object[]{batchSize, publishInitial, removeHandler, false});
                }
            }
        }
        return data.toArray(new Object[][]{});
    }

    @Override
    public Publisher<Long> createPublisher(final long elements) {
        final HandlerPublisher<Long> publisher = new HandlerPublisher<>(Long.class);

        final BatchedProducer out;
        if (scheduled) {
            out = new ScheduledBatchedProducer(executor, 5);
        } else {
            out = new BatchedProducer();
        }
        out.sequence(publishInitial)
                .batchSize(batchSize)
                .eofOn(elements);

        final LocalChannel channel = new LocalChannel();
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
}
