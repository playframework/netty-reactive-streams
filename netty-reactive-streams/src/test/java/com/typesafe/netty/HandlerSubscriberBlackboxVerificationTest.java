package com.typesafe.netty;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import org.reactivestreams.tck.flow.FlowSubscriberBlackboxVerification;
import org.reactivestreams.tck.TestEnvironment;

public class HandlerSubscriberBlackboxVerificationTest extends FlowSubscriberBlackboxVerification<Long> {

    public HandlerSubscriberBlackboxVerificationTest() {
        super(new TestEnvironment());
    }

    @Override
    public Subscriber<Long> createFlowSubscriber() {
        // Embedded channel requires at least one handler when it's created, but HandlerSubscriber
        // needs the channels event loop in order to be created, so start with a dummy, then replace.
        ChannelHandler dummy = new ChannelDuplexHandler();
        EmbeddedChannel channel = new EmbeddedChannel(dummy);
        HandlerSubscriber<Long> subscriber = new HandlerSubscriber<>(channel.eventLoop(), 2, 4);
        channel.pipeline().replace(dummy, "subscriber", subscriber);

        return new SubscriberWithChannel<>(channel, subscriber);
    }

    @Override
    public Long createElement(int element) {
        return (long) element;
    }

    @Override
    public void triggerFlowRequest(Subscriber<? super Long> subscriber) {
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
