package com.typesafe.netty;

import io.netty.channel.ChannelHandlerContext;

/**
 * Decides how messages received by a handler should be published.
 *
 * Every message received by the {@link HandlerPublisher} will be passed to either the {@link #transform(Object, ChannelHandlerContext)}
 * method, or the {@link #messageDropped(Object, ChannelHandlerContext)} method, but never both.
 */
public interface PublisherMessageHandler<T> {

    /**
     * Transform the given message into a subscriber event.
     *
     * This allows both refinement and/or transformation of the message itself, as well as allows controlling how the
     * publisher should handle messages received by the handler.
     *
     * If {@link Next} is returned, the message will be passed to the subscriber. If {@link Complete} is returned, the
     * subscriber will be completed. If {@link Error} is returned, the subscriber will be passed the error.
     * If {@link Drop} is returned, the message will be dropped, it won't be handled in any way.
     *
     * In addition to transforming the message, this method is also passed the {@link ChannelHandlerContext} for the
     * handler, which gives this method the opportunity to interact with the channel and pipeline. For example, when
     * a certain type of message is received, this handler may decide to remove itself from the pipeline, or close the
     * channel.
     *
     * This method is always invoked synchronously from the {@link io.netty.channel.ChannelInboundHandler#channelRead(ChannelHandlerContext, Object)}
     * method, which means, for example, that if it modifies the handler pipeline, the next message will be received
     * by the modified channel pipeline.
     *
     * The transformed events may or may not be immediately passed to the subscriber, depending on the demand received
     * frem the subscriber, and may never get passed to the subscriber at all, if the subscriber cancels.
     *
     * @param message The message to transform.
     * @param ctx The channel handler context of the {@link HandlerPublisher}.
     * @return The message transformed into an event for the subscriber.
     */
    SubscriberEvent<T> transform(Object message, ChannelHandlerContext ctx);

    /**
     * Invoked when the handler receives a message but can't publish it because the subscriber has cancelled or has
     * been completed or passed an error.
     *
     * This is useful, for example, to continue handling messages if the subscriber cancels early.  An HTTP request body
     * publisher may use this to wait for the LastHttpContent, so that it can be removed from the handler chain at the
     * right time.
     *
     * This method will be invoked synchronously from the {@link io.netty.channel.ChannelInboundHandler#channelRead(ChannelHandlerContext, Object)}.
     *
     * Messages that have previously been passed to the {@link #transform(Object, ChannelHandlerContext)} method will
     * never be passed to this method, even if they end up being dropped because while they were buffered, the
     * subscriber cancelled.
     *
     * @param message The message that was dropped.
     * @param ctx The channel handler context of the {@link HandlerPublisher}.
     */
    void messageDropped(Object message, ChannelHandlerContext ctx);

    /**
     * Invoked when the stream is cancelled for any reason other than a complete or error event returned by the
     * transform method.
     *
     * This includes: the subscriber cancelled; an error in the stream occurred; the stream was closed; the
     * subscriber does something that violates the spec.
     *
     * @param ctx The channel handler context of the {@link HandlerPublisher}.
     */
    void cancel(ChannelHandlerContext ctx);

    /**
     * A subscriber event.
     *
     * Describes how a message should be transformed into an event for a subscriber.
     */
    interface SubscriberEvent<T> {}

    /**
     * A next event.
     *
     * The message that this event wraps will be passed to the subscribers onNext method.
     */
    final class Next<T> implements SubscriberEvent<T> {
        private final T message;

        public Next(T message) {
            this.message = message;
        }

        public T getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return "Next{" +
                    "message=" + message +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Next<?> next = (Next<?>) o;

            return message.equals(next.message);

        }

        @Override
        public int hashCode() {
            return message.hashCode();
        }
    }

    /**
     * A complete event.
     *
     * This event will be passed to the subscribers onComplete method.
     */
    final class Complete<T> implements SubscriberEvent<T> {
        private static final Complete INSTANCE = new Complete();

        private Complete() {}

        @SuppressWarnings("unchecked")
        public static <T> Complete<T> instance() {
            return INSTANCE;
        }

        @Override
        public String toString() {
            return "Complete";
        }
    }

    /**
     * An error event.
     *
     * The exception that this event wraps will be passed to the subscribers onError method.
     */
    final class Error<T> implements SubscriberEvent<T> {
        private final Throwable error;

        public Error(Throwable error) {
            this.error = error;
        }

        public Throwable getError() {
            return error;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Error<?> error1 = (Error<?>) o;

            return error.equals(error1.error);

        }

        @Override
        public int hashCode() {
            return error.hashCode();
        }

        @Override
        public String toString() {
            return "Error{" +
                    "error=" + error +
                    '}';
        }
    }

    /**
     * A drop event.
     *
     * If this event is returned, the message is dropped, and not passed to the subscriber.
     */
    final class Drop<T> implements SubscriberEvent<T> {
        private static final Drop INSTANCE = new Drop();

        private Drop() {}

        @SuppressWarnings("unchecked")
        public static <T> Drop<T> instance() {
            return INSTANCE;
        }

        @Override
        public String toString() {
            return "Drop";
        }
    }

}
