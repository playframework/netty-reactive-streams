package com.typesafe.netty;

import io.netty.channel.ChannelHandlerContext;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A batched producer.
 *
 * Responds to read requests with batches of elements according to batch size. When eofOn is reached, it closes the
 * channel.
 */
public class ScheduledBatchedProducer extends BatchedProducer {

    private final ScheduledExecutorService executor;
    private final long delay;

    public ScheduledBatchedProducer(ScheduledExecutorService executor, long delay) {
        this.executor = executor;
        this.delay = delay;
    }

    @Override
    public void read(final ChannelHandlerContext ctx) throws Exception {
        executor.schedule(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < batchSize && sequence != eofOn; i++) {
                    ctx.fireChannelRead(sequence);
                    sequence++;
                }
                executor.schedule(new Runnable() {
                    @Override
                    public void run() {
                        if (eofOn == sequence) {
                            ctx.fireChannelInactive();
                        } else {
                            ctx.fireChannelReadComplete();
                        }
                    }
                }, delay, TimeUnit.MILLISECONDS);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }
}
