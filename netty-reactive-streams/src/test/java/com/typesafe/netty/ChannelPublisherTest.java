package com.typesafe.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Promise;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

public class ChannelPublisherTest {

    private EventLoopGroup group;
    private Channel channel;
    private Publisher<Channel> publisher;
    private SubscriberProbe<Channel> subscriber;

    @BeforeMethod
    public void start() throws Exception {
        group = new NioEventLoopGroup();
        EventLoop eventLoop = group.next();

        HandlerPublisher<Channel> handlerPublisher = new HandlerPublisher<>(eventLoop, Channel.class);
        Bootstrap bootstrap = new Bootstrap();

        bootstrap
                .channel(NioServerSocketChannel.class)
                .group(eventLoop)
                .option(ChannelOption.AUTO_READ, false)
                .handler(handlerPublisher)
                .localAddress("127.0.0.1", 0);

        channel = bootstrap.bind().await().channel();
        this.publisher = handlerPublisher;

        subscriber = new SubscriberProbe<>();
    }

    @AfterMethod
    public void stop() throws Exception {
        channel.unsafe().closeForcibly();
        group.shutdownGracefully();
    }

    @Test
    public void test() throws Exception {
        publisher.subscribe(subscriber);
        Subscription sub = subscriber.takeSubscription();

        // Try one cycle
        sub.request(1);
        Socket socket1 = connect();
        receiveConnection();
        readWriteData(socket1, 1);

        // Check back pressure
        Socket socket2 = connect();
        subscriber.expectNoElements();

        // Now request the next connection
        sub.request(1);
        receiveConnection();
        readWriteData(socket2, 2);

        // Close the channel
        channel.close();
        subscriber.expectNoElements();
        subscriber.expectComplete();
    }

    private Socket connect() throws Exception {
        InetSocketAddress address = (InetSocketAddress) channel.localAddress();
        return new Socket(address.getAddress(), address.getPort());
    }

    private void readWriteData(Socket socket, int data) throws Exception {
        OutputStream os = socket.getOutputStream();
        os.write(data);
        os.flush();
        InputStream is = socket.getInputStream();
        int received = is.read();
        socket.close();
        assertEquals(received, data);
    }

    private void receiveConnection() throws Exception {
        Channel channel = subscriber.take();
        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                ctx.writeAndFlush(msg);
            }
        });
        group.register(channel);
    }

    private class SubscriberProbe<T> implements Subscriber<T> {
        final BlockingQueue<Subscription> subscriptions = new LinkedBlockingQueue<>();
        final BlockingQueue<T> elements = new LinkedBlockingQueue<>();
        final Promise<Void> promise = new DefaultPromise<>(group.next());

        public void onSubscribe(Subscription s) {
            subscriptions.add(s);
        }

        public void onNext(T t) {
            elements.add(t);
        }

        public void onError(Throwable t) {
            promise.setFailure(t);
        }

        public void onComplete() {
            promise.setSuccess(null);
        }

        Subscription takeSubscription() throws Exception {
            Subscription sub = subscriptions.poll(100, TimeUnit.MILLISECONDS);
            assertNotNull(sub);
            return sub;
        }

        T take() throws Exception {
            T t = elements.poll(100, TimeUnit.MILLISECONDS);
            assertNotNull(t);
            return t;
        }

        void expectNoElements() throws Exception {
            T t = elements.poll(100, TimeUnit.MILLISECONDS);
            assertNull(t);
        }

        void expectComplete() throws Exception {
            promise.get(100, TimeUnit.MILLISECONDS);
        }
    }
}
