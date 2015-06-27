package com.typesafe.netty;

import io.netty.channel.ChannelHandlerContext;

/**
 * A message handler of longs that, when the long equals the passed in completeOn parameter, will return complete.
 *
 * If it receives a negative number, it will return error. Otherwise it returns the number as the next element.
 */
public class BoundedMessageHandler implements PublisherMessageHandler<Long> {
    private final long completeOn;
    private boolean completed = false;
    private boolean cancelled = false;

    public BoundedMessageHandler(long completeOn) {
        this.completeOn = completeOn;
    }

    public SubscriberEvent<Long> transform(Object message, ChannelHandlerContext ctx) {
        if (completed) {
            throw new IllegalStateException("Received a message after returning complete");
        }
        if (cancelled) {
            throw new IllegalStateException("Received a message after being cancelled");
        }
        long item = (Long) message;
        if (item == completeOn && completeOn != Long.MAX_VALUE) {
            completed = true;
            return Complete.instance();
        } else if (item < 0) {
            completed = true;
            return new Error<>(new IllegalArgumentException("Negative item"));
        } else {
            return new Next<>(item);
        }
    }
    public void messageDropped(Object message, ChannelHandlerContext ctx) {
        if (cancelled) {
            throw new IllegalStateException("Received a message after being cancelled");
        }
    }
    public void cancel(ChannelHandlerContext ctx) {
        if (cancelled) {
            throw new IllegalStateException("Cancelled twice");
        }
        cancelled = true;
    }
}
