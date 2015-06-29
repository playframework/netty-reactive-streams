package com.typesafe.netty;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalEventLoopGroup;
import org.reactivestreams.Subscriber;
import org.reactivestreams.tck.SubscriberWhiteboxVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class HandlerSubscriberWhiteboxVerificationTest extends SubscriberWhiteboxVerification<Long> {

    private boolean workAroundIssue277;

    public HandlerSubscriberWhiteboxVerificationTest() {
        super(new TestEnvironment());
    }

    private LocalEventLoopGroup eventLoop;

    // I tried making this before/after class, but encountered a strange error where after 32 publishers were created,
    // the following tests complained about the executor being shut down when I registered the channel. Though, it
    // doesn't happen if you create 32 publishers in a single test.
    @BeforeMethod
    public void startEventLoop() {
        workAroundIssue277 = false;
        eventLoop = new LocalEventLoopGroup();
    }

    @AfterMethod
    public void stopEventLoop() {
        eventLoop.shutdownGracefully();
        eventLoop = null;
    }

    @Override
    public Subscriber<Long> createSubscriber(WhiteboxSubscriberProbe<Long> probe) {

        final HandlerSubscriber<Long> subscriber = new HandlerSubscriber<>(2, 4);
        final ProbeHandler<Long> probeHandler = new ProbeHandler<>(probe, Long.class);

        final LocalChannel channel = new LocalChannel() {
            private final ChannelMetadata metadata = new ChannelMetadata(true);
            @Override
            public ChannelMetadata metadata() {
                return metadata;
            }
        };
        ChannelFuture future = eventLoop.register(channel).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                channel.pipeline().addLast("probe", probeHandler);
                channel.pipeline().addLast("subscriber", subscriber);
            }
        });

        if (workAroundIssue277) {
            try {
                // Wait for the pipeline to be setup, so we're ready to receive elements even if they aren't requested,
                // because https://github.com/reactive-streams/reactive-streams-jvm/issues/277
                future.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        return probeHandler.wrap(subscriber);
    }

    @Override
    public void required_spec208_mustBePreparedToReceiveOnNextSignalsAfterHavingCalledSubscriptionCancel() throws Throwable {
        // See https://github.com/reactive-streams/reactive-streams-jvm/issues/277
        workAroundIssue277 = true;
        super.required_spec208_mustBePreparedToReceiveOnNextSignalsAfterHavingCalledSubscriptionCancel();
    }

    @Override
    public Long createElement(int element) {
        return (long) element;
    }

}
