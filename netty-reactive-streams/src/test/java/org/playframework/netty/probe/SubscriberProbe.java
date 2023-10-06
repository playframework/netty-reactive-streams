package org.playframework.netty.probe;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class SubscriberProbe<T> extends Probe implements Subscriber<T> {

    private final Subscriber<T> subscriber;

    public SubscriberProbe(Subscriber<T> subscriber, String name) {
        super(name);
        this.subscriber = subscriber;
    }

    SubscriberProbe(Subscriber<T> subscriber, String name, long start) {
        super(name, start);
        this.subscriber = subscriber;
    }

    @Override
    public void onSubscribe(final Subscription s) {
        String sName = s == null ? "null" : s.getClass().getName();
        log("invoke onSubscribe with subscription " + sName);
        subscriber.onSubscribe(new Subscription() {
            @Override
            public void request(long n) {
                log("invoke request " + n);
                s.request(n);
                log("finish request");
            }

            @Override
            public void cancel() {
                log("invoke cancel");
                s.cancel();
                log("finish cancel");
            }
        });
        log("finish onSubscribe");
    }

    @Override
    public void onNext(T t) {
        log("invoke onNext with message " + t);
        subscriber.onNext(t);
        log("finish onNext");
    }

    @Override
    public void onError(Throwable t) {
        String tName = t == null ? "null" : t.getClass().getName();
        log("invoke onError with " + tName);
        subscriber.onError(t);
        log("finish onError");
    }

    @Override
    public void onComplete() {
        log("invoke onComplete");
        subscriber.onComplete();
        log("finish onComplete");
    }
}
