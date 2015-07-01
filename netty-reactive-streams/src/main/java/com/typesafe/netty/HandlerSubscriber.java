package com.typesafe.netty;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.typesafe.netty.HandlerSubscriber.State.*;

/**
 * Subscriber that publishes received messages to the handler pipeline.
 */
public class HandlerSubscriber<T> extends ChannelDuplexHandler implements Subscriber<T> {

    public static final long DEFAULT_LOW_WATERMARK = 4;
    public static final long DEFAULT_HIGH_WATERMARK = 16;

    /**
     * Create a new handler subscriber.
     *
     * @param demandLowWatermark The low watermark for demand. When demand drops below this, more will be requested.
     * @param demandHighWatermark The high watermark for demand. This is the maximum that will be requested.
     */
    public HandlerSubscriber(long demandLowWatermark, long demandHighWatermark) {
        this.demandLowWatermark = demandLowWatermark;
        this.demandHighWatermark = demandHighWatermark;
    }

    public HandlerSubscriber() {
        this(DEFAULT_LOW_WATERMARK, DEFAULT_HIGH_WATERMARK);
    }

    /**
     * Override for custom error handling. By default, it closes the channel.
     */
    protected void error(Throwable error) {
        doClose();
    }

    /**
     * Override for custom completion handling. By default, it closes the channel.
     */
    protected void complete() {
        doClose();
    }

    private final long demandLowWatermark;
    private final long demandHighWatermark;

    /**
     * This state is used for when a context or subscription haven't been provided yet, and is only accessed
     * atomically.
     */
    enum AtomicState {
        NO_SUBSCRIPTION_OR_CONTEXT,
        NO_SUBSCRIPTION,
        NO_CONTEXT,
        DONE
    }

    /**
     * These are the main states of the subscriber, used after a context has been provided, and only accessed
     * through that context.
     */
    enum State {
        NO_SUBSCRIPTION,
        INACTIVE,
        RUNNING,
        CANCELLED,
        COMPLETE
    }

    private final AtomicBoolean hasSubscription = new AtomicBoolean();
    private final AtomicReference<AtomicState> subscriptionContextState = new AtomicReference<>(AtomicState.NO_SUBSCRIPTION_OR_CONTEXT);

    private volatile Subscription subscription;
    private volatile ChannelHandlerContext ctx;

    private State state = NO_SUBSCRIPTION;
    private long outstandingDemand = 0;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;

        if (subscriptionContextState.compareAndSet(AtomicState.NO_SUBSCRIPTION_OR_CONTEXT, AtomicState.NO_SUBSCRIPTION)) {
            // We were in no subscription or context, now we just don't have a subscription.
            state = NO_SUBSCRIPTION;
        } else if (subscriptionContextState.compareAndSet(AtomicState.NO_CONTEXT, AtomicState.DONE)) {
            subscriptionContextState.set(AtomicState.DONE);

            // We were in no context, we're now fully initialised
            maybeStart();
        } else {
            // We are complete, close
            state = COMPLETE;
            ctx.close();
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        maybeRequestMore();
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        if (state == INACTIVE) {
            state = RUNNING;
            maybeRequestMore();
        }
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancel();
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        cancel();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cancel();
        ctx.fireExceptionCaught(cause);
    }

    private void cancel() {
        switch (state) {
            case NO_SUBSCRIPTION:
                state = CANCELLED;
                break;
            case RUNNING:
            case INACTIVE:
                subscription.cancel();
                state = CANCELLED;
                break;
        }
    }

    @Override
    public void onSubscribe(final Subscription subscription) {
        if (subscription == null) {
            throw new NullPointerException("Null subscription");
        } else if (!hasSubscription.compareAndSet(false, true)) {
            subscription.cancel();
        } else {
            this.subscription = subscription;
            if (subscriptionContextState.compareAndSet(AtomicState.NO_SUBSCRIPTION_OR_CONTEXT, AtomicState.NO_CONTEXT)) {
                // We had neither subscription or context, now we just don't have a subscription
            } else {
                subscriptionContextState.set(AtomicState.DONE);
                ctx.executor().execute(new Runnable() {
                    @Override
                    public void run() {
                        provideSubscription();
                    }
                });
            }
        }
    }

    private void provideSubscription() {
        switch (state) {
            case NO_SUBSCRIPTION:
                maybeStart();
                break;
            case CANCELLED:
                subscription.cancel();
                break;
        }
    }

    private void maybeStart() {
        if (ctx.channel().isActive()) {
            state = RUNNING;
            maybeRequestMore();
        } else {
            state = INACTIVE;
        }
    }

    @Override
    public void onNext(T t) {

        // Publish straight to the context.
        ctx.writeAndFlush(t).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {

                outstandingDemand--;
                maybeRequestMore();
            }
        });
    }

    @Override
    public void onError(final Throwable error) {
        if (error == null) {
            throw new NullPointerException("Null error published");
        }
        error(error);
    }

    @Override
    public void onComplete() {
        complete();
    }

    private void doClose() {
        // First try the no context path
        if (!subscriptionContextState.compareAndSet(AtomicState.NO_CONTEXT, AtomicState.DONE)) {
            // We must have a context, so close it
            ctx.close();
        }
    }

    private void maybeRequestMore() {
        if (outstandingDemand <= demandLowWatermark && ctx.channel().isWritable()) {
            long toRequest = demandHighWatermark - outstandingDemand;

            outstandingDemand = demandHighWatermark;
            subscription.request(toRequest);
        }
    }
}
