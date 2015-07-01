package com.typesafe.netty.http;

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import org.reactivestreams.Publisher;

class DelegateHttpRequest extends DelegateHttpMessage implements HttpRequest {

    protected final HttpRequest request;

    public DelegateHttpRequest(HttpRequest request) {
        super(request);
        this.request = request;
    }

    @Override
    public HttpRequest setMethod(HttpMethod method) {
        request.setMethod(method);
        return this;
    }

    @Override
    public HttpRequest setUri(String uri) {
        request.setUri(uri);
        return this;
    }

    @Override
    public HttpMethod getMethod() {
        return request.getMethod();
    }

    @Override
    public String getUri() {
        return request.getUri();
    }

    @Override
    public HttpRequest setProtocolVersion(HttpVersion version) {
        super.setProtocolVersion(version);
        return this;
    }
}
