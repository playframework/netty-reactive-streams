package com.typesafe.netty.http;

import akka.japi.Pair;
import akka.japi.function.Creator;
import akka.stream.Materializer;
import akka.stream.javadsl.*;
import org.reactivestreams.FlowAdapters;
import java.util.concurrent.Flow.Processor;

public class AkkaStreamsUtil {

    public static <In, Out> Processor<In, Out> flowToProcessor(Flow<In, Out, ?> flow, Materializer materializer) {
        Pair<org.reactivestreams.Subscriber<In>, org.reactivestreams.Publisher<Out>> pair =
                Source.<In>asSubscriber()
                        .via(flow)
                        .toMat(Sink.<Out>asPublisher(AsPublisher.WITH_FANOUT), Keep.both())
                        .run(materializer);

        return new DelegateProcessor<>(FlowAdapters.toFlowSubscriber(pair.first()), FlowAdapters.toFlowPublisher(pair.second()));
    }

    public static <In, Out> Flow<In, Out, ?> processorToFlow(final Processor<In, Out> processor) {
        return Flow.fromProcessor(new Creator<org.reactivestreams.Processor<In, Out>>() {
            @Override
            public org.reactivestreams.Processor<In, Out> create() throws Exception {
                return FlowAdapters.toProcessor(processor);
            }
        });
    }
}
