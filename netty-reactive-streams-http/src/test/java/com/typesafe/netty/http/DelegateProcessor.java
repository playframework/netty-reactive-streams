package com.typesafe.netty.http;

import java.util.concurrent.Flow.Processor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;

public class DelegateProcessor<In, Out> implements Processor<In, Out> {

    private final Subscriber<In> subscriber;
    private final Publisher<Out> publisher;

    public DelegateProcessor(Subscriber<In> subscriber, Publisher<Out> publisher) {
        this.subscriber = subscriber;
        this.publisher = publisher;
    }

    @Override
    public void subscribe(Subscriber<? super Out> s) {
        publisher.subscribe(s);
    }

    @Override
    public void onSubscribe(Subscription s) {
        subscriber.onSubscribe(s);
    }

    @Override
    public void onNext(In in) {
        subscriber.onNext(in);
    }

    @Override
    public void onError(Throwable t) {
        subscriber.onError(t);
    }

    @Override
    public void onComplete() {
        subscriber.onComplete();
    }
}
