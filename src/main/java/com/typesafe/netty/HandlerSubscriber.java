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

    public HandlerSubscriber(long demandLowWatermark, long demandHighWatermark) {
        this.demandLowWatermark = demandLowWatermark;
        this.demandHighWatermark = demandHighWatermark;
    }

    private final long demandLowWatermark;
    private final long demandHighWatermark;

    enum State {
        NO_SUBSCRIPTION_OR_CONTEXT,
        NO_SUBSCRIPTION,
        NO_CONTEXT,
        RUNNING,
        CANCELLED,
        COMPLETE
    }

    private final AtomicBoolean hasSubscription = new AtomicBoolean();
    private final AtomicReference<State> subscriptionContextState = new AtomicReference<>(NO_SUBSCRIPTION_OR_CONTEXT);

    private volatile Subscription subscription;
    private volatile ChannelHandlerContext ctx;

    private State state = NO_SUBSCRIPTION_OR_CONTEXT;
    private long outstandingDemand = 0;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {

        this.ctx = ctx;
        if (subscriptionContextState.compareAndSet(NO_SUBSCRIPTION_OR_CONTEXT, NO_SUBSCRIPTION)) {
            // We were in no subscription or context, now we just don't have a subscription.
            state = NO_SUBSCRIPTION;
        } else if (subscriptionContextState.compareAndSet(NO_CONTEXT, RUNNING)) {
            // We were in no context, now we're running
            state = RUNNING;
            maybeRequestMore();
        } else {
            // We are complete, disconnect
            state = COMPLETE;
            ctx.disconnect();
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        maybeRequestMore();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancel();
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
            if (subscriptionContextState.compareAndSet(NO_SUBSCRIPTION_OR_CONTEXT, NO_CONTEXT)) {
                // We had neither subscription or context, now we just don't have a subscription
            } else {
                subscriptionContextState.set(RUNNING);
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
                state = RUNNING;
                maybeRequestMore();
                break;
            case CANCELLED:
                subscription.cancel();
                break;
        }
    }

    @Override
    public void onNext(T t) {
        // Publish straight to the context.
        // TODO determine if this can ever throw an exception, eg if the channel is closed, or if the handler is removed
        // from the pipeline
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
        complete();
    }

    @Override
    public void onComplete() {
        complete();
    }

    private void complete() {
        // First try the no context path
        if (!subscriptionContextState.compareAndSet(NO_CONTEXT, COMPLETE)) {
            // We must have a context, so disconnect it
            ctx.disconnect();
        }
    }

    private void maybeRequestMore() {
        if (outstandingDemand <= demandLowWatermark && ctx.channel().isWritable()) {
            subscription.request(demandHighWatermark - outstandingDemand);
            outstandingDemand = demandHighWatermark;
        }
    }
}
