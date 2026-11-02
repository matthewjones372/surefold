package dev.surefold.stream

import dev.surefold.Clock
import dev.surefold.LogLevel
import dev.surefold.LogLine
import dev.surefold.Logger
import dev.surefold.TestClock
import dev.surefold.VirtualThreads
import dev.surefold.clock
import dev.surefold.logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * surefold-stream on surefold's own threads: a run is one pull loop on one fork from [on]. Every stage runs on that
 * fork, when the stage after it asks, so a stage body can block and `bind`. The operators that run
 * something beside the loop (`mapPar`, `buffer`, `merge`, `flatMapMerge`, `conflate`, `groupedWithin`)
 * start it from [on] as well (a `mapPar` given an executor of its own keeps it), and the run lets go of
 * every one of them before its exit completes. Time is surefold's `clock`, read when the run starts.
 */
class Forks(private val on: Executor = VirtualThreads, name: String = "Forks") : StreamBackend {

    /** The name a refusal says a run was started on: a backend built on this one gives its own. */
    @StreamSpi
    override val key: BackendKey = if (name == ForksKey.name) ForksKey else BackendKey(name)

    @StreamSpi
    override fun runs(node: Node): Boolean = node.pulls()

    @StreamSpi
    override fun <E, R : Any> materialise(run: Run<E, R>): Running<E, R> {
        // Read here, on the caller's thread: the fork inherits neither.
        val running = PullRun<E, R>(logger.get(), clock.get(), on)
        on.execute { running.drain(run) }
        return running
    }
}

internal val ForksKey = BackendKey("Forks")

/**
 * The [Forks] pull loop on a clock a test moves: what surefold-stream-test's `TestStreams` is, by its own
 * [name]. It runs what Forks does, and `tick`, `groupedWithin` and `restartOnDefect` besides, which wait
 * on [time] and on nothing else, and `mapPar`, one element at a time in the order they came.
 *
 * A run is workers that take turns, so one stage runs at a time and in the same order every time. `start`
 * returns once the run is over or waiting on a later time, and each move of [time] returns once
 * everything due by then has run, in time order. A stage body that blocks on anything but [time] blocks
 * the test with it.
 */
@StreamSpi
class ForksOnClock(private val time: TestClock, name: String) : StreamBackend {

    @StreamSpi
    override val key: BackendKey = BackendKey(name)

    @StreamSpi
    override fun runs(node: Node): Boolean = node.pulls()

    @StreamSpi
    override fun <E, R : Any> materialise(run: Run<E, R>): Running<E, R> {
        val turns = Turns(time)
        val waiting = time.register(turns)
        val running = PullRun<E, R>(
            logger.get(),
            time,
            VirtualThreads,
            onStop = turns::stop,
            kept = CopyOnWriteArrayList(),
            interrupting = false,
            turns = turns,
        )
        turns.fork {
            try {
                running.drain(run)
            } finally {
                // Whatever still waits in the run is let go, and the clock no longer stops for it.
                turns.end()
                waiting.close()
            }
        }
        turns.settle()
        return running
    }
}

/** A run that keeps each element that reached its end, so a test can read them before it is over. */
@StreamSpi
interface Emitting {
    fun emitted(): List<Any>
}

private class PullRun<E, R : Any>(
    private val log: Logger,
    private val clock: Clock,
    private val on: Executor,
    private val onStop: () -> Unit = {},
    private val kept: MutableList<Any>? = null,
    /** Whether a stop interrupts the loop: on Forks yes, on a test's clock the workers are parked instead. */
    private val interrupting: Boolean = true,
    /** The turns of a run on a test's clock; none on Forks. */
    private val turns: Turns? = null,
) : Running<E, R>, Emitting {

    private val stopped = AtomicBoolean(false)

    /** The thread running the loop, while it runs it: a stop interrupts it there and nowhere else. */
    private var loop: Thread? = null

    /** The run's loop, once the fork has opened it: a stop before then is seen as it opens. */
    @Volatile
    private var pulling: Pulling<E, R>? = null

    override val exit = CompletableFuture<Exit<E, R>>()

    override fun emitted(): List<Any> = kept?.toList() ?: error("only a run on a test's clock keeps what it emitted")

    /**
     * Ends the run now: the loop stops before its next element, and whatever it is waiting on (a body in
     * flight, an empty buffer, a stage that blocks) is interrupted. The exit is `Done` with what the end
     * had by then.
     */
    override fun stop() {
        stopped.set(true)
        // Woken before the interrupt, so a blocking read the interrupt lands in ends the stream, not the run.
        pulling?.stop()
        if (interrupting) synchronized(this) { loop?.interrupt() }
        onStop()
    }

    override fun close() {
        stop()
        exit.join()
    }

    // The catch is as wide as the run: an exit that never completes is a caller waiting for ever.
    @Suppress("TooGenericExceptionCaught")
    fun drain(run: Run<E, R>) {
        synchronized(this) { loop = Thread.currentThread() }
        var opened: Pulling<E, R>? = null
        val ended = try {
            val loop = Pulling(run, log, clock, on, kept, boundaries = null, turns).also { opened = it }
            pulling = loop
            if (stopped.get()) loop.stop()
            checkNotNull(loop.pull(Int.MAX_VALUE)) { "a pull of every element ended without an exit" }
        } catch (unopened: Throwable) {
            log.log(
                LogLine(LogLevel.Error, unopened.oneLine(), clock.now(), unopened, origin = PullRun::class.java.name),
            )
            Exit.Died(unopened)
        } finally {
            synchronized(this) {
                loop = null
                // A stop that came after the loop's last wait leaves the flag set; the thread may be pooled.
                Thread.interrupted()
            }
        }
        // After the interrupt is spent, so a close that talks to a server is not cut short by it.
        exit.complete(opened?.finish(ended) ?: ended)
    }
}

/**
 * One run's pull loop, as far as it has got: what [Forks] pulls to the end in one go, and what a backend that
 * shares its threads between runs pulls a batch at a time. Its pulls carry the run's resources and forks with
 * them, so the batches may each be on a different thread, as long as it is one at a time.
 */
@StreamSpi
class Pulling<E, R : Any> internal constructor(
    run: Run<E, R>,
    private val log: Logger,
    private val clock: Clock,
    on: Executor,
    private val kept: MutableList<Any>?,
    private val boundaries: Boundaries?,
    /** The turns of a run on a test's clock, which its pulls take turns on; none on Forks. */
    private val turns: Turns?,
) {
    constructor(
        run: Run<E, R>,
        log: Logger,
        clock: Clock,
        on: Executor,
        kept: MutableList<Any>? = null,
        boundaries: Boundaries? = null,
    ) : this(run, log, clock, on, kept, boundaries, turns = null)

    // The fused tree is the same for every run of a description, so it is worked out once, and so is whether
    // a run of it can start a thread of its own: one that cannot pays nothing for it.
    private val compiled = run.compiled.getOrCompile(ForksKey) { Compiled(run.node.optimised()) }

    /** Every blocking source this run opened: woken by a stop, closed before the exit completes. */
    private val resources = Resources()
    private val releases = if (compiled.forks) Releases(on, clock) else null
    private val sink = sinkFor(run.end)
    private val stopped = AtomicBoolean(false)
    private var pull: Pull? = null

    /** The run ends before its next element, and whatever a source is blocked in is woken. */
    fun stop() {
        stopped.set(true)
        resources.wake()
    }

    /**
     * Pulls up to [max] elements into the end: the exit once the run has ended, and null while it goes on. The
     * nodes built as it pulls are offered to the run's [Boundaries] first.
     */
    fun pull(max: Int): Exit<E, R>? = pulled(max)

    /** The exit of a run that stops where it is: `Done` with what the end has so far. */
    @Suppress("UNCHECKED_CAST")
    fun done(): Exit<E, R> = Exit.Done(sink.value() as R)

    /**
     * Lets go of everything the run holds, and answers the exit it ends with: [ended], unless letting go threw.
     * What is still in flight is let go of first, and then the sources are closed; a close that throws is a
     * defect, and ends a run that would otherwise have been `Done`.
     */
    // The catch is as wide as a release: whatever one threw, the run still ends.
    @Suppress("TooGenericExceptionCaught")
    fun finish(ended: Exit<E, R>): Exit<E, R> {
        val released = try {
            releases?.releaseAll()
            ended
        } catch (unreleased: Throwable) {
            val origin = Pulling::class.java.name
            log.log(LogLine(LogLevel.Error, unreleased.oneLine(), clock.now(), unreleased, origin = origin))
            Exit.Died(unreleased)
        }
        val unclosed = resources.close() ?: return released
        if (released !is Exit.Done) return released
        log.log(
            LogLine(LogLevel.Error, unclosed.oneLine(), clock.now(), unclosed, origin = Pulling::class.java.name),
        )
        return Exit.Died(unclosed)
    }

    /** The run's pull, built under the run's context on the first batch and kept for the ones after. */
    private fun opened(): Pull {
        pull?.let { return it }
        val context = PullContext(turns, releases, resources, boundaries)
        return with(context) { compiled.tree.pull() }.also { pull = it }
    }

    // The catch is as wide as a pipeline, because everything a stage threw ends the run: a declared
    // failure as `Failed`, an interruption a stop caused as `Done`, and anything else as the `Died` it
    // is logged as.
    @Suppress("TooGenericExceptionCaught", "UNCHECKED_CAST")
    private fun pulled(max: Int): Exit<E, R>? =
        try {
            val from = opened()
            var left = max
            var ended: Exit<E, R>? = null
            while (ended == null && left > 0) {
                val a = if (stopped.get()) null else from.next()
                if (a == null) {
                    ended = done()
                } else {
                    kept?.add(a)
                    sink.add(a)
                    left--
                }
            }
            ended
        } catch (failure: DeclaredFailure) {
            Exit.Failed(failure.declared())
        } catch (defect: Throwable) {
            if (stopped.get() && defect.isInterruption()) {
                done()
            } else {
                log.log(
                    LogLine(LogLevel.Error, defect.oneLine(), clock.now(), defect, origin = Pulling::class.java.name),
                )
                Exit.Died(defect)
            }
        }
}

/** What the end of a run has so far, kept as it goes so that a stop keeps it too. */
private sealed interface Sink {
    fun add(a: Any)

    fun value(): Any

    class Collecting : Sink {
        private val collected = ArrayList<Any>()

        override fun add(a: Any) {
            collected.add(a)
        }

        override fun value(): Any = collected
    }

    class Folding(private val fold: End.Fold) : Sink {
        private var folded: Any = fold.zero

        override fun add(a: Any) {
            folded = fold.f(folded, a)
        }

        override fun value(): Any = folded
    }
}

private fun sinkFor(end: End): Sink =
    when (end) {
        End.Collect -> Sink.Collecting()
        is End.Fold -> Sink.Folding(end)
        is End.Native -> error("${end.builder} reached the Forks runner, which start refuses it before")
    }

/** An interruption, or a failure it caused: what a stop leaves behind in whatever it woke. */
internal fun Throwable.isInterruption(): Boolean =
    generateSequence(this) { it.cause }.take(CAUSES_READ).any { it is InterruptedException }

/** How deep a cause chain is read for an interruption: far enough for a wrapper or two, and no cycle. */
private const val CAUSES_READ = 8

/** The line the Pekko backend logs a defect with, so a defect reads the same whichever backend ran it. */
private fun Throwable.oneLine(): String {
    val defect = suppressed.filterIsInstance<Defect>().firstOrNull()
    return if (defect == null) "surefold-stream: $this" else "surefold-stream: ${defect.message}: $this"
}

/** A description compiled for Forks, once: its fused tree, and whether a run of it can start a thread. */
internal class Compiled(val tree: Node) {
    val forks: Boolean = tree.mayFork()
}

/**
 * Whether pulling this starts a thread of its own, builds a stream later that might (a `flatMap`'s inner
 * streams and a `catchAll`'s recovery are built only when they are needed), or reads the run's clock.
 */
private fun Node.mayFork(): Boolean =
    when (this) {
        is Node.MapPar, is Node.MapAsync, is Node.Buffer, is Node.Merge, is Node.Conflate, is Node.FlatMap,
        is Node.CatchAll, is Node.Tick, is Node.GroupedWithin, is Node.RestartOnDefect,
        -> true

        else -> children().any { it.mayFork() }
    }
