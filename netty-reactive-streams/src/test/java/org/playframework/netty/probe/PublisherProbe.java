package org.playframework.netty.probe;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

public class PublisherProbe<T> extends Probe implements Publisher<T> {

    private final Publisher<T> publisher;

    public PublisherProbe(Publisher<T> publisher, String name) {
        super(name);
        this.publisher = publisher;
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        String sName = s == null ? "null" : s.getClass().getName();
        log("invoke subscribe with subscriber " + sName);
        publisher.subscribe(new SubscriberProbe<>(s, name, start));
        log("finish subscribe");
    }
}
