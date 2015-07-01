package com.typesafe.netty;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.reactivestreams.tck.SubscriberWhiteboxVerification;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

public class ProbeHandler<T> extends ChannelDuplexHandler implements SubscriberWhiteboxVerification.SubscriberPuppet {

    private static final int NO_CONTEXT = 0;
    private static final int RUN = 1;
    private static final int CANCEL = 2;

    private final SubscriberWhiteboxVerification.WhiteboxSubscriberProbe<T> probe;
    private final Class<T> clazz;
    private final Queue<WriteEvent> queue = new LinkedList<>();
    private final AtomicInteger state = new AtomicInteger(NO_CONTEXT);
    private volatile ChannelHandlerContext ctx;
    // Netty doesn't provide a way to send errors out, so we capture whether it was an error or complete here
    private volatile Throwable receivedError = null;

    public ProbeHandler(SubscriberWhiteboxVerification.WhiteboxSubscriberProbe<T> probe, Class<T> clazz) {
        this.probe = probe;
        this.clazz = clazz;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
        if (!state.compareAndSet(NO_CONTEXT, RUN)) {
            ctx.fireChannelInactive();
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        queue.add(new WriteEvent(clazz.cast(msg), promise));
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise future) throws Exception {
        if (receivedError == null) {
            probe.registerOnComplete();
        } else {
            probe.registerOnError(receivedError);
        }
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        while (!queue.isEmpty()) {
            WriteEvent event = queue.remove();
            probe.registerOnNext(event.msg);
            event.future.setSuccess();
        }
    }

    @Override
    public void triggerRequest(long elements) {
        // No need, the channel automatically requests
    }

    @Override
    public void signalCancel() {
        if (!state.compareAndSet(NO_CONTEXT, CANCEL)) {
            ctx.fireChannelInactive();
        }
    }

    private class WriteEvent {
        final T msg;
        final ChannelPromise future;

        private WriteEvent(T msg, ChannelPromise future) {
            this.msg = msg;
            this.future = future;
        }
    }

    public Subscriber<T> wrap(final Subscriber<T> subscriber) {
        return new Subscriber<T>() {
            public void onSubscribe(Subscription s) {
                probe.registerOnSubscribe(ProbeHandler.this);
                subscriber.onSubscribe(s);
            }
            public void onNext(T t) {
                subscriber.onNext(t);
            }
            public void onError(Throwable t) {
                receivedError = t;
                subscriber.onError(t);
            }
            public void onComplete() {
                subscriber.onComplete();
            }
        };
    }
}
