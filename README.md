# Netty Reactive Streams

This provides a reactive streams implementation for Netty.  Essentially it comes in the form of two channel handlers, one that publishes inbound messages received on a channel to a `Publisher`, and another that writes messages received by a `Subscriber` outbound.

Features include:

* Full backpressure support, as long as the `AUTO_READ` channel option is disabled.
* Publishers/subscribers can be dynamically added and removed from the pipeline.
* Multiple publishers/subscribers can be inserted into the pipeline.
* Customisable cancel/complete/failure handling.

## Using the publisher/subscriber

Here's an example of creating a client channel that publishes/subscribes to `ByteBuf`'s:

```java
EventLoopGroup workerGroup = new NioEventLoopGroup();

Bootstrap bootstrap = new Bootstrap()
  .group(workerGroup)
  .channel(NioSocketChannel.class)
  .option(ChannelOption.SO_KEEPALIVE, true);
  .handler(new ChannelInitializer<SocketChannel>() {
    @Override
    public void initChannel(SocketChannel ch) throws Exception {
      HandlerPublisher<ByteBuf> publisher = new HandlerPublisher<>(ch.executor(),
        ByteBuf.class);
      HandlerSubscriber<ByteBuf> subscriber = new HandlerSubscriber<>(ch.executor());

      // Here you can subscriber to the publisher, and pass the subscriber to a publisher.

      ch.pipeline().addLast(publisher, subscriber);
    }
});
```

Notice that the channels executor is explicitly passed to the publisher and subscriber constructors.  `HandlerPublisher` and `HandlerSubscriber` use Netty's happens before guarantees made by its `EventExecutor` interface in order to handle asynchronous events from reactive streams, hence they need to have an executor to do this with.  This executor must also be the same executor that the handler is registered to use with the channel, so that all channel events are fired from the same context.  The publisher/subscriber will throw an exception if the handler is registered with a different executor.

## Netty Reactive Streams HTTP

In addition to raw reactive streams support, the `netty-reactive-streams-http` library provides some HTTP model abstractions and a handle for implementing HTTP servers/clients that use reactive streams to stream HTTP message bodies.  The `HttpStreamsServerHandler` and `HttpStreamsClientHandler` ensure that every `HttpRequest` and `HttpResponse` in the pipeline is either a `FullHttpRequest` or `FullHttpResponse`, for when the body can be worked with in memory, or `StreamedHttpRequest` or `StreamedHttpResponse`, which are messages that double as `Publisher<HttpContent>` for handling the request/response body.
