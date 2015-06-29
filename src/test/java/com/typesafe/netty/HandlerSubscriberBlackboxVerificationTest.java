package com.typesafe.netty;

import io.netty.channel.embedded.EmbeddedChannel;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.reactivestreams.tck.SubscriberBlackboxVerification;
import org.reactivestreams.tck.TestEnvironment;

/**
 * Created by jroper on 29/06/15.
 */
public class HandlerSubscriberBlackboxVerificationTest extends SubscriberBlackboxVerification<Long> {

    public HandlerSubscriberBlackboxVerificationTest() {
        super(new TestEnvironment());
    }

    @Override
    public Subscriber<Long> createSubscriber() {
        HandlerSubscriber<Long> subscriber = new HandlerSubscriber<>(2, 4);
        EmbeddedChannel channel = new EmbeddedChannel(subscriber);

        return new SubscriberWithChannel<Long>(channel, subscriber);
    }

    @Override
    public Long createElement(int element) {
        return (long) element;
    }

    @Override
    public void triggerRequest(Subscriber<? super Long> subscriber) {
        EmbeddedChannel channel = ((SubscriberWithChannel) subscriber).channel;

        channel.runPendingTasks();
        while (channel.readOutbound() != null) {
            channel.runPendingTasks();
        }
        channel.runPendingTasks();
    }

    /**
     * Delegate subscriber that makes the embedded channel available so we can talk to it to trigger a request.
     */
    private static class SubscriberWithChannel<T> implements Subscriber<T> {
        final EmbeddedChannel channel;
        final HandlerSubscriber<T> subscriber;

        public SubscriberWithChannel(EmbeddedChannel channel, HandlerSubscriber<T> subscriber) {
            this.channel = channel;
            this.subscriber = subscriber;
        }

        public void onSubscribe(Subscription s) {
            subscriber.onSubscribe(s);
        }

        public void onNext(T t) {
            subscriber.onNext(t);
        }

        public void onError(Throwable t) {
            subscriber.onError(t);
        }

        public void onComplete() {
            subscriber.onComplete();
        }
    }
}
