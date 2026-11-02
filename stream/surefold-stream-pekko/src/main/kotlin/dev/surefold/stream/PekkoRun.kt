@file:OptIn(SourceSeam::class)

package dev.surefold.stream

import dev.surefold.await
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.stream.Attributes
import org.apache.pekko.stream.UniqueKillSwitch
import org.apache.pekko.stream.javadsl.Sink
import java.util.concurrent.CompletionStage

/**
 * A run described, to the sink named: the sink's materialised value is the run's.
 *
 * A materialised value that is not a `CompletionStage` is refused at the type: [run] would have nothing to wait on.
 */
fun <E, A : Any, M : Any> Stream<E, A>.runWith(sink: Sink<A, CompletionStage<M>>): Run<E, M> =
    Run(node, End.Native(sink, Pekko, "runWith", pekkoSite()))

/** Pekko Streams on [system], the backend surefold-stream started on. */
class PekkoStreams(private val system: ClassicActorSystemProvider) : StreamBackend {

    @StreamSpi
    override val key: BackendKey get() = Pekko

    @StreamSpi
    override fun <E, R : Any> materialise(run: Run<E, R>): Running<E, R> {
        val hooks = RunHooks()
        val (switch, exit) = run.started(system, hooks)
        return PekkoRunning(switch, hooks, exit)
    }
}

/** [run] on [PekkoStreams], which is what naming a system has always meant. */
fun <E, R : Any> Run<E, R>.run(system: ClassicActorSystemProvider): CompletionStage<Exit<E, R>> =
    run(PekkoStreams(system))

fun <E, R : Any> Run<E, R>.start(system: ClassicActorSystemProvider): Running<E, R> = start(PekkoStreams(system))

private class PekkoRunning<E, R>(
    private val switch: UniqueKillSwitch,
    private val hooks: RunHooks,
    override val exit: CompletionStage<Exit<E, R>>,
) : Running<E, R> {

    /** A run with a source that drains, such as a Kafka consumer, is stopped by that drain, not the kill switch. */
    override fun stop() {
        if (!hooks.stop()) switch.shutdown()
    }

    override fun close() {
        stop()
        exit.await()
    }
}

private fun <E, R : Any> Run<E, R>.started(
    system: ClassicActorSystemProvider,
    hooks: RunHooks,
): Pair<UniqueKillSwitch, CompletionStage<Exit<E, R>>> {
    // A sink authored in Java completes with null whatever its type argument says, and Kotlin would
    // hand that back as a Done whose value is typed non-null. The same guard Stream.fromStage has. The
    // line it names was read when the run was described, so starting one walks no stack.
    val at = end.site
    val materialised = graph.addAttributes(Attributes.apply(RunHooksAttribute(hooks))).run(system)

    @Suppress("UNCHECKED_CAST")
    val exit = (materialised.second() as CompletionStage<R>)
        .checked { NullPointerException("$NULL_MATERIALISED, built at $at") }
        .thenApply<Exit<E, R>> { value -> Exit.Done(value) }
        .exceptionally { thrown -> thrown.asExit<E, R>().reportedTo(system) }
        // A blocking source is closed after a sink that cancelled it completes; the exit waits for that, so a
        // caller finds every resource closed, as on Forks. A close that threw ends an otherwise Done run Died.
        .thenCompose { ended ->
            hooks.closed().handle { _, thrown ->
                if (thrown != null && ended is Exit.Done) {
                    Exit.Died(thrown.unwrapped()).reportedTo<E, R>(system)
                } else {
                    ended
                }
            }
        }
        .whenComplete { _, _ -> hooks.ended() }
    return materialised.first() to exit
}

private const val NULL_MATERIALISED = "surefold-stream: run ended with null"

/** Where the end of a run was written. `Sink.seq` never completes with null, so collecting names itself. */
private val End.site: String
    get() = when (this) {
        End.Collect -> "runCollect"
        is End.Fold -> at
        is End.Native -> at
    }

private fun <E, R> Throwable.asExit(): Exit<E, R> {
    val cause = unwrapped()
    return if (cause is DeclaredFailure) Exit.Failed(cause.declared()) else Exit.Died(cause)
}

/**
 * A defect is logged before the `Exit` carrying it is handed back, because the common way to lose
 * one is a stage nobody reads.
 */
private fun <E, R> Exit<E, R>.reportedTo(system: ClassicActorSystemProvider): Exit<E, R> {
    if (this is Exit.Died) system.classicSystem().log().error(cause, cause.oneLine())
    return this
}

/** One line: the three facts where the cause carries them apart, and then the cause itself. */
private fun Throwable.oneLine(): String {
    val defect = suppressed.filterIsInstance<Defect>().firstOrNull()
    return if (defect == null) "surefold-stream: $this" else "surefold-stream: ${defect.message}: $this"
}
