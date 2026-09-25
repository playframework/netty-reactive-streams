package org.playframework.netty;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.reactivestreams.Subscription;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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

    @Test
    public void doesNotRequestMoreAfterCompletionWhileWritesArePending() {
        assertNoDemandAfterTerminalSignalWhileWritesArePending(HandlerSubscriber::onComplete);
    }

    @Test
    public void doesNotRequestMoreAfterErrorWhileWritesArePending() {
        assertNoDemandAfterTerminalSignalWhileWritesArePending(
                subscriber -> subscriber.onError(new RuntimeException("failed")));
    }

    private void assertNoDemandAfterTerminalSignalWhileWritesArePending(
            Consumer<HandlerSubscriber<Object>> terminalSignal) {
        List<ChannelPromise> pendingWrites = new ArrayList<>();
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                pendingWrites.add(promise);
            }
        });

        try {
            HandlerSubscriber<Object> subscriber = new HandlerSubscriber<>(channel.eventLoop(), 2, 4);
            RecordingSubscription subscription = new RecordingSubscription();
            channel.pipeline().addLast(subscriber);

            subscriber.onSubscribe(subscription);
            channel.runPendingTasks();
            assertEquals(subscription.requested.get(), 4);

            subscriber.onNext("first");
            subscriber.onNext("second");
            terminalSignal.accept(subscriber);

            assertEquals(pendingWrites.size(), 2);
            pendingWrites.get(0).setSuccess();
            pendingWrites.get(1).setSuccess();

            assertEquals(subscription.requested.get(), 4, "must not request more after a terminal signal");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void doesNotRequestMoreWhenWritableAgainAfterCompletion() {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelDuplexHandler());

        try {
            HandlerSubscriber<Object> subscriber = new HandlerSubscriber<Object>(channel.eventLoop(), 2, 4) {
                @Override
                protected void complete() {
                    // Keep the channel open so a later writability event still reaches this subscriber.
                }
            };
            RecordingSubscription subscription = new RecordingSubscription();
            channel.pipeline().addLast(subscriber);
            subscriber.onSubscribe(subscription);
            channel.runPendingTasks();
            assertEquals(subscription.requested.get(), 4);

            // Consume demand down to the low watermark while unwritable, so nothing is requested yet
            channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
            subscriber.onNext("first");
            subscriber.onNext("second");
            subscriber.onComplete();

            // Delivers the writability changes that the outbound buffer queued
            channel.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
            channel.runPendingTasks();

            assertEquals(subscription.requested.get(), 4, "must not request more after onComplete");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void doesNotRequestMoreWhenCompletedDuringWritabilityCheck() {
        assertNoDemandWhenTerminatedDuringWritabilityCheck(HandlerSubscriber::onComplete);
    }

    @Test
    public void doesNotRequestMoreWhenFailedDuringWritabilityCheck() {
        assertNoDemandWhenTerminatedDuringWritabilityCheck(
                subscriber -> subscriber.onError(new RuntimeException("failed")));
    }

    private void assertNoDemandWhenTerminatedDuringWritabilityCheck(
            Consumer<HandlerSubscriber<Object>> terminalSignal) {
        TerminatingChannel channel = new TerminatingChannel();

        try {
            HandlerSubscriber<Object> subscriber = new HandlerSubscriber<>(channel.eventLoop(), 2, 4);
            RecordingSubscription subscription = new RecordingSubscription();
            channel.pipeline().addLast(subscriber);

            subscriber.onSubscribe(subscription);
            channel.runPendingTasks();
            assertEquals(subscription.requested.get(), 4);

            // Consume demand down to the low watermark while unwritable, so nothing is requested yet
            channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
            subscriber.onNext("first");
            subscriber.onNext("second");
            assertEquals(subscription.requested.get(), 4);

            // The terminal signal arrives while the event loop checks writability, just before it would request
            channel.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
            channel.onWritabilityCheck = () -> terminalSignal.accept(subscriber);
            channel.pipeline().fireChannelWritabilityChanged();

            assertEquals(subscription.requested.get(), 4, "must not request more after a terminal signal");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Runs a callback from inside isWritable(), between the demand checks and the request.
     */
    private static final class TerminatingChannel extends EmbeddedChannel {
        Runnable onWritabilityCheck;

        TerminatingChannel() {
            super(new ChannelDuplexHandler());
        }

        @Override
        public boolean isWritable() {
            Runnable callback = onWritabilityCheck;
            onWritabilityCheck = null;
            if (callback != null) {
                callback.run();
            }
            return super.isWritable();
        }
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
