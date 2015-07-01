package com.typesafe.netty.http;

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.reactivestreams.Publisher;

class DelegateHttpResponse extends DelegateHttpMessage implements HttpResponse {

    protected final HttpResponse response;

    public DelegateHttpResponse(HttpResponse response) {
        super(response);
        this.response = response;
    }

    @Override
    public HttpResponse setStatus(HttpResponseStatus status) {
        response.setStatus(status);
        return this;
    }

    @Override
    public HttpResponseStatus getStatus() {
        return response.getStatus();
    }

    @Override
    public HttpResponse setProtocolVersion(HttpVersion version) {
        super.setProtocolVersion(version);
        return this;
    }
}
