// In surefold-stream's package, beside surefold-kafka's runCollect and runFold guards, so importing
// surefold-stream-pekko's runWith imports this too.
package dev.surefold.stream

import dev.surefold.kafka.Committed
import org.apache.pekko.stream.javadsl.Sink
import java.util.concurrent.CompletionStage

private const val COMMIT = "A stream of Committed records ends on runCommitting, or its offsets are never committed."

@Deprecated(COMMIT, level = DeprecationLevel.ERROR)
@Suppress("UnusedParameter") // The signature is what refuses the call; nothing can reach the body.
fun <E, A : Any, M : Any> Stream<E, Committed<A>>.runWith(sink: Sink<Committed<A>, CompletionStage<M>>): Run<E, M> =
    error(COMMIT)
