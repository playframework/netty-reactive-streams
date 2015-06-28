package com.typesafe.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

/**
 * A batched producer.
 *
 * Responds to read requests with batches of elements according to batch size. When eofOn is reached, it closes the
 * channel.
 */
public class BatchedProducer extends ChannelOutboundHandlerAdapter {

    protected boolean sendComplete = false;
    protected long eofOn = Long.MAX_VALUE;
    protected int batchSize = 1;
    protected long sequence = 0;

    private boolean cancelled = false;

    @Override
    public void read(final ChannelHandlerContext ctx) throws Exception {
        if (cancelled) {
            throw new IllegalStateException("Received demand after being cancelled");
        }
        ctx.executor().execute(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < batchSize && sequence != eofOn; i++) {
                    ctx.fireChannelRead(sequence);
                    sequence++;
                }
                if (eofOn == sequence) {
                    if (sendComplete) {
                        ctx.fireChannelRead(HandlerPublisher.COMPLETE);
                    } else {
                        ctx.fireChannelInactive();
                    }
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

    public BatchedProducer sendComplete(boolean sendComplete) {
        this.sendComplete = sendComplete;
        return this;
    }

    public BatchedProducer batchSize(int batchSize) {
        this.batchSize = batchSize;
        return this;
    }

    public BatchedProducer eofOn(long eofOn) {
        this.eofOn = eofOn;
        return this;
    }

    public BatchedProducer sequence(long sequence) {
        this.sequence = sequence;
        return this;
    }
}
