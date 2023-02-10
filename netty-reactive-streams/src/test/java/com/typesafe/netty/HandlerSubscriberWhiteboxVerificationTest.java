package com.typesafe.netty;

import io.netty.channel.*;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Promise;
import org.reactivestreams.tck.flow.FlowSubscriberWhiteboxVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

import java.util.concurrent.Flow.Subscriber;

public class HandlerSubscriberWhiteboxVerificationTest extends FlowSubscriberWhiteboxVerification<Long> {

    private boolean workAroundIssue277;

    public HandlerSubscriberWhiteboxVerificationTest() {
        super(new TestEnvironment());
    }

    private DefaultEventLoopGroup eventLoop;

    // I tried making this before/after class, but encountered a strange error where after 32 publishers were created,
    // the following tests complained about the executor being shut down when I registered the channel. Though, it
    // doesn't happen if you create 32 publishers in a single test.
    @BeforeMethod
    public void startEventLoop() {
        workAroundIssue277 = false;
        eventLoop = new DefaultEventLoopGroup();
    }

    @AfterMethod
    public void stopEventLoop() {
        eventLoop.shutdownGracefully();
        eventLoop = null;
    }

    @Override
    public Subscriber<Long> createFlowSubscriber(WhiteboxSubscriberProbe<Long> probe) {


        final ClosedLoopChannel channel = new ClosedLoopChannel();
        channel.config().setAutoRead(false);
        ChannelFuture registered = eventLoop.register(channel);

        final HandlerSubscriber<Long> subscriber = new HandlerSubscriber<>(registered.channel().eventLoop(), 2, 4);
        final ProbeHandler<Long> probeHandler = new ProbeHandler<>(probe, Long.class);
        final Promise<Void> handlersInPlace = new DefaultPromise<>(eventLoop.next());

        registered.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                channel.pipeline().addLast("probe", probeHandler);
                channel.pipeline().addLast("subscriber", subscriber);
                handlersInPlace.setSuccess(null);
                // Channel needs to be active before the subscriber starts responding to demand
                channel.pipeline().fireChannelActive();
            }
        });

        if (workAroundIssue277) {
            try {
                // Wait for the pipeline to be setup, so we're ready to receive elements even if they aren't requested,
                // because https://github.com/reactive-streams/reactive-streams-jvm/issues/277
                handlersInPlace.await();
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
    public void required_spec308_requestMustRegisterGivenNumberElementsToBeProduced() throws Throwable {
        workAroundIssue277 = true;
        super.required_spec308_requestMustRegisterGivenNumberElementsToBeProduced();
    }

    @Override
    public Long createElement(int element) {
        return (long) element;
    }

}
