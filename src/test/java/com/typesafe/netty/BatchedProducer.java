package com.typesafe.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;

/**
 * A batched producer.
 *
 * Responds to read requests with batches of elements according to batch size. When eofOn is reached, it closes the
 * channel.
 */
public class BatchedProducer extends ChannelOutboundHandlerAdapter {

    protected long eofOn = Long.MAX_VALUE;
    protected int batchSize = 1;
    protected long sequence = 0;

    @Override
    public void read(final ChannelHandlerContext ctx) throws Exception {
        ctx.executor().execute(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < batchSize && sequence != eofOn; i++) {
                    ctx.fireChannelRead(sequence);
                    sequence++;
                }
                if (eofOn == sequence) {
                    ctx.fireChannelInactive();
                } else {
                    ctx.fireChannelReadComplete();
                }
            }
        });
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
