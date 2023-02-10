package com.typesafe.netty;

import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;


/**
 * A cancelled subscriber.
 */
public final class CancelledSubscriber<T> implements Subscriber<T> {

    @Override
    public void onSubscribe(Subscription subscription) {
        if (subscription == null) {
            throw new NullPointerException("Null subscription");
        } else {
            subscription.cancel();
        }
    }

    @Override
    public void onNext(T t) {
    }

    @Override
    public void onError(Throwable error) {
        if (error == null) {
            throw new NullPointerException("Null error published");
        }
    }

    @Override
    public void onComplete() {
    }
}
