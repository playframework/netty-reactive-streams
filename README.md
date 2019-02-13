# Netty Reactive Streams

[![Build Status](https://travis-ci.org/playframework/netty-reactive-streams.svg?branch=master)](https://travis-ci.org/playframework/netty-reactive-streams)

This provides a reactive streams implementation for Netty.  Essentially it comes in the form of two channel handlers, one that publishes inbound messages received on a channel to a `Publisher`, and another that writes messages received by a `Subscriber` outbound.

Features include:

* Full backpressure support, as long as the `AUTO_READ` channel option is disabled.
* Publishers/subscribers can be dynamically added and removed from the pipeline.
* Multiple publishers/subscribers can be inserted into the pipeline.
* Customisable cancel/complete/failure handling.

## Releasing a new version

This project is released and published through Sonatype.  You must have Sonatype credentials installed, preferably in `$HOME/.m2/settings.xml` and a GPG key that is available to the standard GPG keyservers.  If your key is not on the server, you should add it to the keyserver as follows:

```
gpg --send-key <MY_KEYID> --keyserver pgp.mit.edu
```

After that, you can perform a release through the following commands:

```
mvn release:prepare -Darguments=-Dgpg.passphrase=thephrase
mvn release:perform -Darguments=-Dgpg.passphrase=thephrase
```

This will push a release to the staging repository and automatically close and publish the staging repository to production.

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

## A word of caution with ByteBuf's

Reactive streams allows implementations to drop messages when a stream is cancelled or failed - Netty reactive streams does this itself.  This introduces a problem when those messages hold resources and must be cleaned up, for example, if they are `ByteBuf's` that need to be released.  While Netty reactive streams will call `ReferenceCountUtil.release()` on every message that it drops, other implementations that it's interacting with likely won't do this.

For this reason, you should make sure you do one of the following:

* Insert a handler in the pipeline that converts incoming `ByteBuf`'s to some non reference counted structure, for example `byte[]`, or some high level immutable message structure, and have the `HandlerPublisher` publish those.  Similarly, the `HandlerSubscriber` should subscribe to some non reference counted structure, and a handler should be placed in the pipeline to convert these structures to `ByteBuf`'s.
* Write a wrapping `Publisher` and `Subscriber` that synchronously converts `ByteBuf`'s to/from a non reference counted structure.

## Netty Reactive Streams HTTP

In addition to raw reactive streams support, the `netty-reactive-streams-http` library provides some HTTP model abstractions and a handle for implementing HTTP servers/clients that use reactive streams to stream HTTP message bodies.  The `HttpStreamsServerHandler` and `HttpStreamsClientHandler` ensure that every `HttpRequest` and `HttpResponse` in the pipeline is either a `FullHttpRequest` or `FullHttpResponse`, for when the body can be worked with in memory, or `StreamedHttpRequest` or `StreamedHttpResponse`, which are messages that double as `Publisher<HttpContent>` for handling the request/response body.

## Support

The Play Mailer library is *[Community Driven][]*.

[Community Driven]: https://developer.lightbend.com/docs/reactive-platform/2.0/support-terminology/index.html#community-driven
