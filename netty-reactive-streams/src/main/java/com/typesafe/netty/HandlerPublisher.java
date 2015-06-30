package com.typesafe.netty;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.TypeParameterMatcher;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import static com.typesafe.netty.HandlerPublisher.State.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publisher for a Netty Handler.
 *
 * This publisher supports only one subscriber.
 *
 * All interactions with the subscriber are done from the handlers executor, hence, they provide the same happens before
 * semantics that Netty provides.
 *
 * The handler publishes all messages that match the type as specified by the passed in class. Any non matching messages
 * are forwarded to the next handler.
 *
 * The publisher will signal complete if it receives a channel inactive event.
 *
 * The publisher will release any messages that it drops (for example, messages that are buffered when the subscriber
 * cancels), but other than that, it does not release any messages.  It is up to the subscriber to release messages.
 *
 * If the subscriber cancels, the publisher will send a disconnect event up the channel pipeline.
 *
 * All errors will short circuit the buffer, and cause publisher to immediately call the subscribers onError method,
 * dropping the buffer.
 *
 * The publisher can be subscribed to or placed in a handler chain in any order.
 */
public class HandlerPublisher<T> extends ChannelDuplexHandler implements Publisher<T> {

    private final TypeParameterMatcher matcher;

    /**
     * Create a handler publisher.
     *
     * @param subscriberMessageType The type of message this publisher accepts.
     */
    public HandlerPublisher(Class<? extends T> subscriberMessageType) {
        this.matcher = TypeParameterMatcher.get(subscriberMessageType);
    }

    /**
     * Returns {@code true} if the given message should be handled. If {@code false} it will be passed to the next
     * {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
     */
    protected boolean acceptInboundMessage(Object msg) throws Exception {
        return matcher.match(msg);
    }

    enum State {
        /**
         * Initial state. There's no subscriber, and no context.
         */
        NO_SUBSCRIBER_OR_CONTEXT,

        /**
         * A subscriber has been provided, but no context has been provided.
         */
        NO_CONTEXT,

        /**
         * A context has been provided, but no subscriber has been provided.
         */
        NO_SUBSCRIBER,

        /**
         * An error has been received, but there's no subscriber to receive it.
         */
        NO_SUBSCRIBER_ERROR,

        /**
         * There is no demand, and we have nothing buffered.
         */
        IDLE,

        /**
         * There is no demand, and we're buffering elements.
         */
        BUFFERING,

        /**
         * We have nothing buffered, but there is demand.
         */
        DEMANDING,

        /**
         * The stream is complete, however there are still elements buffered for which no demand has come from the subscriber.
         */
        DRAINING,

        /**
         * We're done, in the terminal state.
         */
        DONE
    }

    private final Queue<Object> buffer = new LinkedList<>();

    /**
     * Whether a subscriber has been provided. This is used to detect whether two subscribers are subscribing
     * simultaneously.
     */
    private final AtomicBoolean hasSubscriber = new AtomicBoolean();

    /**
     * This is used before a context is provided - once a context is provided, it's not needed, since every event is
     * submitted to the contexts executor to be run on the handlers thread.
     *
     * It only has five possible states, NO_SUBSCRIBER_OR_CONTEXT, NO_SUBSCRIBER, NO_SUBSCRIBER_ERROR, NO_CONTEXT,
     * or DONE.
     */
    private final AtomicReference<State> subscriberContextState = new AtomicReference<>(State.NO_SUBSCRIBER_OR_CONTEXT);
    private State state = NO_SUBSCRIBER_OR_CONTEXT;

    private volatile ChannelHandlerContext ctx;
    private volatile Subscriber<? super T> subscriber;
    private long outstandingDemand = 0;
    private Throwable noSubscriberError;

    @Override
    public void subscribe(final Subscriber<? super T> subscriber) {
        if (subscriber == null) {
            throw new NullPointerException("Null subscriber");
        }

        if (!hasSubscriber.compareAndSet(false, true)) {
            // Must call onSubscribe first.
            subscriber.onSubscribe(new Subscription() {
                @Override
                public void request(long n) {
                }
                @Override
                public void cancel() {
                }
            });
            subscriber.onError(new IllegalStateException("This publisher only supports one subscriber"));
        } else {
            switch(subscriberContextState.get()) {
                case NO_SUBSCRIBER_OR_CONTEXT:
                    this.subscriber = subscriber;
                    if (subscriberContextState.compareAndSet(NO_SUBSCRIBER_OR_CONTEXT, NO_CONTEXT)) {
                        // It's set
                        break;
                    }
                    // Otherwise, a context was supplied since initially checking the state, so let it fall through to
                    // the no subscriber behaviour, which is done on the context executor.
                case NO_SUBSCRIBER:
                case NO_SUBSCRIBER_ERROR:
                    ctx.executor().execute(new Runnable() {
                        @Override
                        public void run() {
                            provideSubscriber(subscriber);
                        }
                    });
                    break;
            }
        }
    }

    private void provideSubscriber(Subscriber<? super T> subscriber) {
        // Subscriber is provided, so set subscriber state to done
        subscriberContextState.set(DONE);

        this.subscriber = subscriber;
        switch (state) {
            case NO_SUBSCRIBER:
                if (buffer.isEmpty()) {
                    state = IDLE;
                } else {
                    state = BUFFERING;
                }
                subscriber.onSubscribe(new ChannelSubscription());
                break;
            case DRAINING:
                subscriber.onSubscribe(new ChannelSubscription());
                break;
            case NO_SUBSCRIBER_ERROR:
                cleanup();
                state = DONE;
                subscriber.onSubscribe(new ChannelSubscription());
                subscriber.onError(noSubscriberError);
                break;
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        switch(subscriberContextState.get()) {
            case NO_SUBSCRIBER_OR_CONTEXT:
                this.ctx = ctx;
                if (subscriberContextState.compareAndSet(NO_SUBSCRIBER_OR_CONTEXT, NO_SUBSCRIBER)) {
                    // It's set, we don't have a subscriber
                    state = NO_SUBSCRIBER;
                    break;
                }
                // We now have a subscriber, let it fall through to the NO_CONTEXT behaviour
            case NO_CONTEXT:
                subscriberContextState.set(DONE);
                this.ctx = ctx;
                state = IDLE;
                subscriber.onSubscribe(new ChannelSubscription());
                break;
            default:
                throw new IllegalStateException("This handler has already been placed in a handler pipeline");
        }
    }

    private void receivedDemand(long demand) {
        switch (state) {
            case BUFFERING:
            case DRAINING:
                if (addDemand(demand)) {
                    flushBuffer();
                }
                break;

            case DEMANDING:
                addDemand(demand);
                break;

            case IDLE:
                if (addDemand(demand)) {
                    ctx.read();
                    state = DEMANDING;
                }
                break;
        }
    }

    private boolean addDemand(long demand) {
        if (demand <= 0) {
            illegalDemand();
            return false;
        } else {
            if (outstandingDemand < Long.MAX_VALUE) {
                outstandingDemand += demand;
                if (outstandingDemand < 0) {
                    outstandingDemand = Long.MAX_VALUE;
                }
            }
            return true;
        }
    }

    private void illegalDemand() {
        cleanup();
        subscriber.onError(new IllegalArgumentException("Request for 0 or negative elements in violation of Section 3.9 of the Reactive Streams specification"));
        ctx.disconnect();
        state = DONE;
    }

    private void flushBuffer() {
        while (!buffer.isEmpty() && (outstandingDemand > 0 || outstandingDemand == Long.MAX_VALUE)) {
            publishMessage(buffer.remove());
        }
        if (buffer.isEmpty()) {
            if (outstandingDemand > 0) {
                if (state == BUFFERING) {
                    state = DEMANDING;
                } // otherwise we're draining
                ctx.read();
            } else if (state == BUFFERING) {
                state = IDLE;
            }
        }
    }

    private void receivedCancel() {
        switch (state) {
            case BUFFERING:
            case DEMANDING:
            case IDLE:
                ctx.disconnect();
            case DRAINING:
                state = DONE;
                break;
        }
        cleanup();
        subscriber = null;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
        if (acceptInboundMessage(message)) {
            switch (state) {
                case IDLE:
                    buffer.add(message);
                    state = BUFFERING;
                    break;
                case NO_SUBSCRIBER:
                case BUFFERING:
                    buffer.add(message);
                    break;
                case DEMANDING:
                    publishMessage(message);
                    break;
                case DRAINING:
                case DONE:
                    ReferenceCountUtil.release(message);
                    break;
                case NO_CONTEXT:
                case NO_SUBSCRIBER_OR_CONTEXT:
                    throw new IllegalStateException("Message received before added to the channel context");
            }
        } else {
            ctx.fireChannelRead(message);
        }
    }

    private void publishMessage(Object message) {
        if (COMPLETE.equals(message)) {
            subscriber.onComplete();
            state = DONE;
        } else {
            @SuppressWarnings("unchecked")
            T next = (T) message;
            subscriber.onNext(next);
            if (outstandingDemand < Long.MAX_VALUE) {
                outstandingDemand--;
                if (outstandingDemand == 0 && state != DRAINING) {
                    if (buffer.isEmpty()) {
                        state = IDLE;
                    } else {
                        state = BUFFERING;
                    }
                }
            }
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        if (state == DEMANDING) {
            ctx.read();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        switch (state) {
            case NO_SUBSCRIBER:
            case NO_SUBSCRIBER_ERROR:
            case BUFFERING:
                buffer.add(COMPLETE);
                state = DRAINING;
                break;
            case DEMANDING:
            case IDLE:
                subscriber.onComplete();
                state = DONE;
                break;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        switch (state) {
            case NO_SUBSCRIBER:
                noSubscriberError = cause;
                state = NO_SUBSCRIBER_ERROR;
                cleanup();
                break;
            case BUFFERING:
            case DEMANDING:
            case IDLE:
            case DRAINING:
                state = DONE;
                cleanup();
                subscriber.onError(cause);
                break;
        }
    }

    /**
     * Release all elements from the buffer.
     */
    private void cleanup() {
        while (!buffer.isEmpty()) {
            ReferenceCountUtil.release(buffer.remove());
        }
    }

    private class ChannelSubscription implements Subscription {
        @Override
        public void request(final long demand) {
            ctx.executor().execute(new Runnable() {
                @Override
                public void run() {
                    receivedDemand(demand);
                }
            });
        }

        @Override
        public void cancel() {
            ctx.executor().execute(new Runnable() {
                @Override
                public void run() {
                    receivedCancel();
                }
            });
        }
    }

    /**
     * Used for buffering a completion signal.
     */
    private static final Object COMPLETE = new Object();
}
