package org.playframework.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A batched producer.
 *
 * Responds to read requests with batches of elements according to batch size. When eofOn is reached, it closes the
 * channel.
 */
public class BatchedProducer extends ChannelOutboundHandlerAdapter {

    protected final long eofOn;
    protected final int batchSize;
    protected final AtomicLong sequence;

    public BatchedProducer(long eofOn, int batchSize, long sequence) {
        this.eofOn = eofOn;
        this.batchSize = batchSize;
        this.sequence = new AtomicLong(sequence);
    }

    private boolean cancelled = false;


    @Override
    public void read(final ChannelHandlerContext ctx) throws Exception {
        if (cancelled) {
            throw new IllegalStateException("Received demand after being cancelled");
        }
        ctx.pipeline().channel().eventLoop().parent().execute(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < batchSize && sequence.get() != eofOn; i++) {
                    ctx.fireChannelRead(sequence.getAndIncrement());
                }
                if (eofOn == sequence.get()) {
                    ctx.fireChannelInactive();
                } else {
                    ctx.fireChannelReadComplete();
                }
            }
        });
    }

    @Override
    public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        if (cancelled) {
            throw new IllegalStateException("Cancelled twice");
        }
        cancelled = true;
    }
}
