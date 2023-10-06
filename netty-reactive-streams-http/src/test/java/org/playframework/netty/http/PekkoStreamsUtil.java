package com.typesafe.netty.http;

import org.apache.pekko.japi.Pair;
import org.apache.pekko.japi.function.Creator;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.javadsl.*;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

public class PekkoStreamsUtil {

    public static <In, Out> Processor<In, Out> flowToProcessor(Flow<In, Out, ?> flow, Materializer materializer) {
        Pair<Subscriber<In>, Publisher<Out>> pair =
                Source.<In>asSubscriber()
                        .via(flow)
                        .toMat(Sink.<Out>asPublisher(AsPublisher.WITH_FANOUT), Keep.<Subscriber<In>, Publisher<Out>>both())
                        .run(materializer);

        return new DelegateProcessor<>(pair.first(), pair.second());
    }

    public static <In, Out> Flow<In, Out, ?> processorToFlow(final Processor<In, Out> processor) {
        return Flow.fromProcessor(new Creator<Processor<In, Out>>() {
            @Override
            public Processor<In, Out> create() throws Exception {
                return processor;
            }
        });
    }
}
