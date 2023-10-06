package com.typesafe.netty;

import io.netty.channel.*;

import java.net.SocketAddress;

/**
 * A closed loop channel that sends no events and receives no events, for testing purposes.
 *
 * Any outgoing events that reach the channel will throw an exception. All events should be caught
 * be inserting a handler that catches them and responds accordingly.
 */
public class ClosedLoopChannel extends AbstractChannel {

    private final ChannelConfig config = new DefaultChannelConfig(this);
    private static final ChannelMetadata metadata = new ChannelMetadata(false);

    private volatile boolean open = true;
    private volatile boolean active = true;

    public ClosedLoopChannel() {
        super(null);
    }

    public void setOpen(boolean open) {
        this.open = open;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new AbstractUnsafe() {
            @Override
            public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return true;
    }

    @Override
    protected SocketAddress localAddress0() {
        throw new UnsupportedOperationException();
    }

    @Override
    protected SocketAddress remoteAddress0() {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void doDisconnect() throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void doClose() throws Exception {
        this.open = false;
    }

    @Override
    protected void doBeginRead() throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public ChannelMetadata metadata() {
        return metadata;
    }
}
