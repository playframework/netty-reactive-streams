package com.typesafe.netty.http;

import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.*;
import org.reactivestreams.Publisher;

class DelegateHttpMessage implements HttpMessage {
    protected final HttpMessage message;

    public DelegateHttpMessage(HttpMessage message) {
        this.message = message;
    }

    @Override
    public HttpVersion getProtocolVersion() {
        return message.getProtocolVersion();
    }

    @Override
    public HttpMessage setProtocolVersion(HttpVersion version) {
        message.setProtocolVersion(version);
        return this;
    }

    @Override
    public HttpHeaders headers() {
        return message.headers();
    }

    @Override
    public DecoderResult getDecoderResult() {
        return message.getDecoderResult();
    }

    @Override
    public void setDecoderResult(DecoderResult result) {
        message.setDecoderResult(result);
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "(" + message.toString() + ")";
    }

}
