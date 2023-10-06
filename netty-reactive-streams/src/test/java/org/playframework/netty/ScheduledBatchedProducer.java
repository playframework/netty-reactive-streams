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

    public ScheduledBatchedProducer(long eofOn, int batchSize, long sequence, ScheduledExecutorService executor, long delay) {
        super(eofOn, batchSize, sequence);
        this.executor = executor;
        this.delay = delay;
    }

    protected boolean complete;

    @Override
    public void read(final ChannelHandlerContext ctx) throws Exception {
        executor.schedule(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < batchSize && sequence.get() != eofOn; i++) {
                    ctx.fireChannelRead(sequence.getAndIncrement());
                }
                complete = eofOn == sequence.get();
                executor.schedule(new Runnable() {
                    @Override
                    public void run() {
                        if (complete) {
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
