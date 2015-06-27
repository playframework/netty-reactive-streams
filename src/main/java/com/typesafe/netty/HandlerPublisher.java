package com.typesafe.netty;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import static com.typesafe.netty.HandlerPublisher.State.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publisher for a Netty Handler.
 *
 * This publisher supports only one subscriber.
 *
 * All interactions with the subscriber are done from the handlers executor, hence, they provide the same happens before
 * semantics that Netty provides.
 *
 * A HandlerPublisher uses a {@link PublisherMessageHandler} to decide how to handle messages it receives. This allows,
 * for example, the publisher to be used to publish a part of the stream, while the handler can be replaced to handle
 * the rest.
 *
 * The publisher can be subscribed to or placed in a handler chain in any order.
 */
public class HandlerPublisher<T> extends ChannelDuplexHandler implements Publisher<T> {

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

    /**
     * Create a HandlerPublisher with the given messageHandler.
     */
    public HandlerPublisher(PublisherMessageHandler<T> messageHandler) {
        this.messageHandler = messageHandler;
    }

    /**
     * Create a HandlerPublisher with an already provided subscriber.
     *
     * Intended for use with a multicast publisher, which creates a new pipeline (connection) for each subscriber
     * received.
     */
    HandlerPublisher(PublisherMessageHandler<T> messageHandler, Subscriber<? super T> subscriber) {
        this(messageHandler);
        subscribe(subscriber);
    }

    private final Queue<PublisherMessageHandler.SubscriberEvent<T>> buffer = new LinkedList<>();

    private final PublisherMessageHandler<T> messageHandler;

    /**
     * This is used before a context is provided - once a context is provided, it's not needed, since every event is
     * submitted to the contexts executor to be run on the handlers thread.
     *
     * It only has three possible states, NO_SUBSCRIBER_OR_CONTEXT, NO_SUBSCRIBER, or DONE, meaning a subscriber has
     * been provided.
     */
    private final AtomicReference<State> subscriberState = new AtomicReference<>(State.NO_SUBSCRIBER_OR_CONTEXT);
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
        switch(subscriberState.get()) {
            case NO_SUBSCRIBER_OR_CONTEXT:
                this.subscriber = subscriber;
                if (subscriberState.compareAndSet(NO_SUBSCRIBER_OR_CONTEXT, NO_CONTEXT)) {
                    // It's set
                    break;
                }
                // Otherwise, we have a context, so let it fall through to the no subscriber behaviour, which is done
                // on the context executor.
            case NO_SUBSCRIBER:
                ctx.executor().execute(new Runnable() {
                    @Override
                    public void run() {
                        // Subscriber is provided, so set subscriber state to done
                        subscriberState.set(DONE);
                        HandlerPublisher.this.subscriber = subscriber;
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
                                state = DONE;
                                subscriber.onSubscribe(new ChannelSubscription());
                                subscriber.onError(noSubscriberError);
                                break;
                        }
                    }
                });
                break;
            default:
                subscriber.onError(new IllegalStateException("This publisher only supports one subscriber"));
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        switch(subscriberState.get()) {
            case NO_SUBSCRIBER_OR_CONTEXT:
                this.ctx = ctx;
                if (subscriberState.compareAndSet(NO_SUBSCRIBER_OR_CONTEXT, NO_SUBSCRIBER)) {
                    // It's set, we don't have a subscriber
                    state = NO_SUBSCRIBER;
                    break;
                }
                // We now have a subscriber, let it fall through to the NO_CONTEXT behaviour
            case NO_CONTEXT:
                subscriberState.set(DONE);
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
        subscriber.onError(new IllegalArgumentException("Request for 0 or negative elements in violation of Section 3.9 of the Reactive Streams specification"));
        messageHandler.cancel(ctx);
        state = DONE;
    }

    private void flushBuffer() {
        while (!buffer.isEmpty() && (outstandingDemand > 0 || outstandingDemand == Long.MAX_VALUE)) {
            publishSubscriberEvent(buffer.remove());
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
                messageHandler.cancel(ctx);
            case DRAINING:
                state = DONE;
                break;
        }
        subscriber = null;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        switch (state) {
            case IDLE:
                idleSubscriberEvent(messageHandler.transform(msg, ctx));
                break;
            case NO_SUBSCRIBER:
            case BUFFERING:
                bufferSubscriberEvent(messageHandler.transform(msg, ctx));
                break;
            case DEMANDING:
                publishSubscriberEvent(messageHandler.transform(msg, ctx));
                break;
            case DRAINING:
            case DONE:
                messageHandler.messageDropped(msg, ctx);
                break;
            case NO_CONTEXT:
            case NO_SUBSCRIBER_OR_CONTEXT:
                throw new IllegalStateException("Message received before added to the channel context");
        }
    }

    private void bufferSubscriberEvent(PublisherMessageHandler.SubscriberEvent<T> event) {
        if (event instanceof PublisherMessageHandler.Next) {
            buffer.add(event);
        } else if (event instanceof PublisherMessageHandler.Complete) {
            buffer.add(event);
            state = DRAINING;
        } else if (event instanceof PublisherMessageHandler.Error) {
            subscriber.onError(((PublisherMessageHandler.Error) event).getError());
            state = DONE;
        } else if (event instanceof PublisherMessageHandler.Drop) {
            // Ignore
        }
    }

    private void idleSubscriberEvent(PublisherMessageHandler.SubscriberEvent<T> event) {
        if (event instanceof PublisherMessageHandler.Next) {
            buffer.add(event);
            state = BUFFERING;
        } else if (event instanceof PublisherMessageHandler.Complete) {
            subscriber.onComplete();
            state = DONE;
        } else if (event instanceof PublisherMessageHandler.Error) {
            subscriber.onError(((PublisherMessageHandler.Error) event).getError());
            state = DONE;
        } else if (event instanceof PublisherMessageHandler.Drop) {
            // Ignore
        }
    }

    private void publishSubscriberEvent(PublisherMessageHandler.SubscriberEvent<T> event) {
        if (event instanceof PublisherMessageHandler.Next) {
            T message = ((PublisherMessageHandler.Next<T>) event).getMessage();
            subscriber.onNext(message);
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
        } else if (event instanceof PublisherMessageHandler.Complete) {
            subscriber.onComplete();
            state = DONE;
        } else if (event instanceof PublisherMessageHandler.Error) {
            subscriber.onError(((PublisherMessageHandler.Error<T>) event).getError());
            state = DONE;
        } else if (event instanceof PublisherMessageHandler.Drop) {
            // Ignore
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
            case BUFFERING:
                bufferSubscriberEvent(PublisherMessageHandler.Complete.<T>instance());
                messageHandler.cancel(ctx);
                state = DRAINING;
                break;
            case DEMANDING:
            case IDLE:
                subscriber.onComplete();
                messageHandler.cancel(ctx);
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
                messageHandler.cancel(ctx);
                break;
            case BUFFERING:
            case DEMANDING:
            case IDLE:
                subscriber.onError(cause);
                messageHandler.cancel(ctx);
                state = DONE;
                break;
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
}
