package com.typesafe.netty.http;

import akka.japi.Pair;
import akka.japi.function.Function2;
import akka.stream.Materializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import scala.runtime.BoxedUnit;

public class AkkaStreamsUtil {

    public static <In, Out> Processor<In, Out> flowToProcessor(Flow<In, Out, ?> flow, Materializer materializer) {
        Pair<Subscriber<In>, Publisher<Out>> pair =
                Source.<In>subscriber()
                        .via(flow)
                        .toMat(Sink.<Out>publisher(), Keep.<Subscriber<In>, Publisher<Out>>both())
                        .run(materializer);

        return new DelegateProcessor<>(pair.first(), pair.second());
    }

    public static <In, Out> Flow<In, Out, ?> processorToFlow(final Processor<In, Out> processor) {
        return Flow.wrap(Sink.<In>publisher(), Source.from(processor), new Function2<Publisher<In>, BoxedUnit, BoxedUnit>() {
            @Override
            public BoxedUnit apply(Publisher<In> publisher, BoxedUnit unit) throws Exception {
                publisher.subscribe(processor);
                return unit;
            }
        });
    }
}
