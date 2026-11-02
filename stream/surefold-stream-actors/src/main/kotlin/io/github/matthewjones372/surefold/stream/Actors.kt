package io.github.matthewjones372.surefold.stream

import dev.surefold.Scope
import dev.surefold.VirtualThreads
import dev.surefold.actor.ActorRef
import dev.surefold.actor.Behaviour
import dev.surefold.actor.Next
import dev.surefold.actor.Signal
import dev.surefold.actor.behaviour
import dev.surefold.actor.onSignal
import dev.surefold.actor.onStart
import dev.surefold.actor.spawn
import dev.surefold.actor.stay
import dev.surefold.actor.stop
import dev.surefold.clock
import dev.surefold.logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/** How many elements a run pulls in one step before it lets its runner go: an actor's throughput. */
private const val BATCH = 64

/**
 * surefold-stream on surefold-actor: a run is an actor of [scope]'s, which pulls the run's loop [batch] elements at a
 * time and then tells itself to go on, so runs share the scope's runners and a long one never holds one. A stage that
 * blocks parks its runner, as any step may. `mapPar` runs on worker actors and `buffer` on a feeder actor, each a
 * child of the run's; the other operators that run something beside the loop start it from [on], as on [Forks].
 * Time is surefold's `clock`, read when the run starts. A run cannot outlive [scope]: its close stops it.
 */
class Actors(
    private val scope: Scope<*>,
    private val batch: Int = BATCH,
    private val on: Executor = VirtualThreads,
    name: String = "Actors",
) : StreamBackend {

    init {
        require(batch > 0) { "a batch must be at least one element, was $batch" }
    }

    // Forks runs every operator the loop does, so it answers for which those are.
    private val forks = Forks(on, name)

    private val live = AtomicInteger()

    /** How many runs started here have not yet ended, for a test to say that none outlived its exit. */
    @StreamSpi
    val running: Int get() = live.get()

    @StreamSpi
    override val key: BackendKey = forks.key

    @StreamSpi
    override fun runs(node: Node): Boolean = forks.runs(node)

    @StreamSpi
    override fun <E, R : Any> materialise(run: Run<E, R>): Running<E, R> {
        // Read here, on the caller's thread: the actor inherits neither.
        val boundaries = ActorBoundaries(batch)
        val pulling = Pulling(run, logger.get(), clock.get(), on, boundaries = boundaries)
        val running = ActorRun(scope, pulling, boundaries, batch, live)
        live.incrementAndGet()
        running.ref = scope.spawn("stream", running.behaviour())
        return running
    }
}

/** What a run's actor tells itself between batches. */
private data object More

private class ActorRun<E, R : Any>(
    private val scope: Scope<*>,
    private val pulling: Pulling<E, R>,
    private val boundaries: ActorBoundaries,
    private val batch: Int,
    private val live: AtomicInteger,
) : Running<E, R> {

    override val exit = CompletableFuture<Exit<E, R>>()

    @Volatile
    lateinit var ref: ActorRef<More>

    // How the loop ended, once it has; read by the actor's own ending, on the actor's own thread.
    private var ended: Exit<E, R>? = null

    fun behaviour(): Behaviour<More, Unit, Nothing> =
        behaviour<More, Unit>(Unit) { ctx, _, _ ->
            val pulled = boundaries.step(ctx) { pulling.pull(batch) }
            if (pulled == null) {
                ctx.self.tell(More)
                stay()
            } else {
                ended = pulled
                // The actor's own stop, not this run's: the member of the same name would stop it from outside.
                Next.Stop
            }
        }.onStart { ctx -> ctx.self.tell(More) }.onSignal { _, _, signal ->
            // The exit completes as the actor ends, however it ends, and after anything the run started has.
            if (signal == Signal.Stopping) {
                live.decrementAndGet()
                exit.complete(pulling.finish(ended ?: pulling.done()))
            }
            stay()
        }

    /**
     * Ends the run now: the loop stops before its next element, a source it is blocked in is woken, and a step
     * that is running is interrupted. The exit is `Done` with what the end had by then.
     */
    override fun stop() {
        pulling.stop()
        scope.stop(ref)
    }

    override fun close() {
        stop()
        exit.join()
    }
}
