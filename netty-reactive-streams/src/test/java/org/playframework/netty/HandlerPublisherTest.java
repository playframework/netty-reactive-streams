package org.playframework.netty;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;

public class HandlerPublisherTest {

    @Test
    public void deliversErrorThatArrivesAfterCompletionBeforeSubscriber() {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelDuplexHandler());
        HandlerPublisher<String> publisher = new HandlerPublisher<>(channel.eventLoop(), String.class);
        channel.pipeline().addLast(publisher);

        try {
            channel.writeInbound("element");
            channel.pipeline().fireChannelInactive();
            channel.pipeline().fireExceptionCaught(new RuntimeException("failed"));

            RecordingSubscriber subscriber = new RecordingSubscriber();
            publisher.subscribe(subscriber);
            channel.runPendingTasks();

            assertEquals(subscriber.events, List.of("onSubscribe", "onError(failed)"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static class RecordingSubscriber implements Subscriber<String> {
        final List<String> events = new ArrayList<>();

        @Override
        public void onSubscribe(Subscription subscription) {
            events.add("onSubscribe");
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(String element) {
            events.add("onNext(" + element + ")");
        }

        @Override
        public void onError(Throwable error) {
            events.add("onError(" + error.getMessage() + ")");
        }

        @Override
        public void onComplete() {
            events.add("onComplete");
        }
    }
}
