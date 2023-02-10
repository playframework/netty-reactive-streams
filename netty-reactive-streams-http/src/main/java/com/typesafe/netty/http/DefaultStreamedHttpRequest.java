package com.typesafe.netty.http;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;

/**
 * A default streamed HTTP request.
 */
public class DefaultStreamedHttpRequest extends DefaultHttpRequest implements StreamedHttpRequest{

    private final Publisher<HttpContent> stream;

    public DefaultStreamedHttpRequest(HttpVersion httpVersion, HttpMethod method, String uri, Publisher<HttpContent> stream) {
        super(httpVersion, method, uri);
        this.stream = stream;
    }

    public DefaultStreamedHttpRequest(HttpVersion httpVersion, HttpMethod method, String uri, boolean validateHeaders, Publisher<HttpContent> stream) {
        super(httpVersion, method, uri, validateHeaders);
        this.stream = stream;
    }

    @Override
    public void subscribe(Subscriber<? super HttpContent> subscriber) {
        stream.subscribe(subscriber);
    }

}
