package com.typesafe.netty.http;

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

final class DelegateStreamedHttpResponse extends DelegateHttpResponse implements StreamedHttpResponse {

    private final Publisher<HttpContent> stream;

    public DelegateStreamedHttpResponse(HttpResponse response, Publisher<HttpContent> stream) {
        super(response);
        this.stream = stream;
    }

    @Override
    public void subscribe(Subscriber<? super HttpContent> subscriber) {
        stream.subscribe(subscriber);
    }
}
