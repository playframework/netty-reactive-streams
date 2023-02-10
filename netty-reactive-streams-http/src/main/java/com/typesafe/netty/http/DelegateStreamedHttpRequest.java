package com.typesafe.netty.http;

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;

final class DelegateStreamedHttpRequest extends DelegateHttpRequest implements StreamedHttpRequest {

    private final Publisher<HttpContent> stream;

    public DelegateStreamedHttpRequest(HttpRequest request, Publisher<HttpContent> stream) {
        super(request);
        this.stream = stream;
    }

    @Override
    public void subscribe(Subscriber<? super HttpContent> subscriber) {
        stream.subscribe(subscriber);
    }
}
