package org.playframework.netty.http;

import org.apache.pekko.japi.Creator;
import org.apache.pekko.japi.Pair;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.javadsl.*;
import java.util.concurrent.Flow.Processor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;

public class PekkoStreamsUtil {

    public static <In, Out> Processor<In, Out> flowToProcessor(Flow<In, Out, ?> flow, Materializer materializer) {
        Pair<Subscriber<In>, Publisher<Out>> pair =
                JavaFlowSupport.Source.<In>asSubscriber()
                        .via(flow)
                        .toMat(JavaFlowSupport.Sink.<Out>asPublisher(AsPublisher.WITH_FANOUT), Keep.<Subscriber<In>, Publisher<Out>>both())
                        .run(materializer);

        return new DelegateProcessor<>(pair.first(), pair.second());
    }

    public static <In, Out> Flow<In, Out, ?> processorToFlow(final Processor<In, Out> processor) {
        try {
            return JavaFlowSupport.Flow.fromProcessor(new Creator<Processor<In, Out>>() {
                @Override
                public Processor<In, Out> create() throws Exception {
                    return processor;
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
