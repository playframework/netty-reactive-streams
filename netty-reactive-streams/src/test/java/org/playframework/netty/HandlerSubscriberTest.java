package org.playframework.netty;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import org.reactivestreams.Subscription;
import org.testng.annotations.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

public class HandlerSubscriberTest {

    @Test
    public void closesChannelWhenCompletedBeforeAddedToPipeline() {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelDuplexHandler());
        HandlerSubscriber<Object> subscriber = new HandlerSubscriber<>(channel.eventLoop(), 2, 4);
        RecordingSubscription subscription = new RecordingSubscription();

        subscriber.onSubscribe(subscription);
        subscriber.onComplete();
        channel.runPendingTasks();
        channel.pipeline().addLast(subscriber);
        channel.runPendingTasks();

        assertFalse(channel.isOpen());
        assertEquals(subscription.requested.get(), 0);
    }

    @Test
    public void closesChannelWhenFailedBeforeAddedToPipeline() {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelDuplexHandler());
        HandlerSubscriber<Object> subscriber = new HandlerSubscriber<>(channel.eventLoop(), 2, 4);
        RecordingSubscription subscription = new RecordingSubscription();

        subscriber.onSubscribe(subscription);
        subscriber.onError(new RuntimeException("failed"));
        channel.runPendingTasks();
        channel.pipeline().addLast(subscriber);
        channel.runPendingTasks();

        assertFalse(channel.isOpen());
        assertEquals(subscription.requested.get(), 0);
    }

    @Test
    public void ignoresWritabilityChangeBeforeSubscription() {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelDuplexHandler());
        HandlerSubscriber<Object> subscriber = new HandlerSubscriber<>(channel.eventLoop(), 2, 4);
        RecordingSubscription subscription = new RecordingSubscription();

        channel.pipeline().addLast(subscriber);
        channel.pipeline().fireChannelWritabilityChanged();
        channel.checkException();
        subscriber.onSubscribe(subscription);
        channel.runPendingTasks();

        assertEquals(subscription.requested.get(), 4);
        assertFalse(subscription.cancelled);
    }

    private static class RecordingSubscription implements Subscription {
        final AtomicLong requested = new AtomicLong();
        volatile boolean cancelled;

        @Override
        public void request(long n) {
            requested.addAndGet(n);
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
