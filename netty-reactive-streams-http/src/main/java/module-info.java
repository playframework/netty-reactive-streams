module com.typesafe.netty.http {
    requires netty.codec.http;
    requires reactive.streams;
    requires java.base;
    requires netty.codec;
    requires netty.buffer;
    requires netty.common;
    requires com.typesafe.netty;
    requires netty.transport;
    exports com.typesafe.netty.http;
}