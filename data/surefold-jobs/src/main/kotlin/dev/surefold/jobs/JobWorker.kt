package dev.surefold.jobs

import dev.surefold.Schedule
import dev.surefold.clock
import dev.surefold.gauge
import dev.surefold.logInfo
import dev.surefold.logWarn
import dev.surefold.postgres.notify.PostgresNotifications
import dev.surefold.surefoldFuture
import io.github.matthewjones372.surefold.stream.Exit
import io.github.matthewjones372.surefold.stream.Forks
import io.github.matthewjones372.surefold.stream.Running
import io.github.matthewjones372.surefold.stream.runFold
import io.github.matthewjones372.surefold.stream.start
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.sql.DataSource
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.toJavaDuration

/**
 * How a [JobWorker] runs. A run's lease is its kind's deadline plus [leaseMargin], so a worker that is slow to write
 * its result back is not mistaken for a dead one. With [perTenant], a claim takes at most that many of one tenant's
 * jobs, so one tenant's burst does not hold every other tenant's jobs behind it. With [listen], a worker holds one
 * connection that `LISTEN`s for jobs enqueued to run now, and claims them without waiting for the next poll.
 */
data class JobSettings(
    val poll: Duration = 1.seconds,
    val leaseMargin: Duration = 30.seconds,
    val drain: Duration = 30.seconds,
    val housekeeping: Duration = 1.minutes,
    val retention: Duration = 7.days,
    val perTenant: Int? = null,
    val listen: Boolean = true,
)

/**
 * Claims due jobs every [JobSettings.poll], up to each kind's concurrency, and runs each on a virtual thread with the
 * bindings [start] was called with. Every [JobSettings.housekeeping] it sweeps expired leases, prunes history, and
 * enqueues the coming firings of each of its [cron] entries.
 *
 * [close] stops claiming and waits up to [JobSettings.drain] for runs in flight, then interrupts the rest: their
 * leases expire and another worker runs them again, which is why a handler must be idempotent.
 */
class JobWorker(
    private val source: DataSource,
    private val handlers: JobHandlers,
    private val settings: JobSettings = JobSettings(),
    val name: String = "${ProcessHandle.current().pid()}-${UUID.randomUUID()}",
    private val cron: List<CronEntry<*>> = emptyList(),
    watchers: List<JobRunWatcher> = JobRunWatchers.registered,
) : AutoCloseable {

    private val queue = Queue(source, name)
    private val run = JobRun(queue, handlers, watchers = watchers)
    private val running = handlers.byName.keys.associateWith { AtomicInteger() }
    private val inFlight = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()

    // The loop sleeps between polls; close interrupts it only while it sleeps, never in the middle of a query.
    private val lock = ReentrantLock()
    private var stopping = false
    private var sleeping: Thread? = null
    private var woken = false

    @Volatile
    private var listener: Running<Nothing, Unit>? = null

    @Volatile
    private var loop: CompletableFuture<Unit>? = null

    /** Starts polling, and answers this worker. */
    fun start(): JobWorker = apply {
        check(loop == null) { "job worker $name has already started" }
        logInfo("surefold-jobs: worker $name started, handling ${handlers.byName.keys.sorted()}")
        loop = surefoldFuture { polling() }
        if (settings.listen) listener = listening()
    }

    /**
     * One connection that `LISTEN`s on [CHANNEL] and wakes the loop for every notification, and for every reconnect,
     * since one sent meanwhile is gone. Its reconnects start at [JobSettings.poll]; a run that ends leaves the loop
     * polling.
     */
    private fun listening(): Running<Nothing, Unit> {
        val reconnect = Schedule.exponential<Throwable>(settings.poll)
            .delayed { _, delay -> minOf(delay, 30.seconds) }
            .jittered(min = 0.5, max = 1.0)
        return PostgresNotifications.listen(source, setOf(CHANNEL), reconnect)
            .runFold(Unit) { _, _ -> wake() }
            .start(Forks())
            .also { running ->
                running.exit.thenAccept { exit ->
                    if (exit is Exit.Died) {
                        logWarn("surefold-jobs: worker $name stopped listening, and only polls: ${exit.cause}")
                    }
                }
            }
    }

    private fun polling() {
        var housekeepingDue = clock.get().now()
        while (true) {
            try {
                if (clock.get().now() >= housekeepingDue) {
                    queue.sweep()
                    queue.prune(settings.retention)
                    enqueueCron()
                    measureBacklog()
                    housekeepingDue = clock.get().now() + settings.housekeeping.toJavaDuration()
                }
                claimAndRun()
            } catch (failed: SQLException) {
                logWarn("surefold-jobs: worker $name could not reach the jobs table, and will try again: $failed")
            }
            if (!pause()) return
        }
    }

    /** Sleeps until the next poll, and answers false once the worker is closing. */
    private fun pause(): Boolean {
        lock.withLock {
            if (stopping) return false
            // A notification that came while the loop was claiming means claim again now.
            if (woken) {
                woken = false
                return true
            }
            sleeping = Thread.currentThread()
        }
        try {
            clock.get().sleep(settings.poll)
        } catch (_: InterruptedException) {
            // Woken by close, or by a job enqueued to run now.
        } finally {
            lock.withLock { sleeping = null }
            Thread.interrupted()
        }
        return lock.withLock {
            woken = false
            !stopping
        }
    }

    /** Ends the loop's sleep, or makes its next one end at once: a job is due now. */
    private fun wake() = lock.withLock {
        woken = true
        sleeping?.interrupt()
    }

    /** Enqueues every cron entry's firings up to the housekeeping after next, so none falls between two passes. */
    internal fun enqueueCron() = cron.forEach { entry -> entry.enqueueAhead(source, settings.housekeeping * 2) }

    /** Sets each handled kind's depth and lag, zero for one with nothing due, so a drained queue reads as drained. */
    internal fun measureBacklog() {
        val backlog = queue.backlog()
        (handlers.byName.keys + backlog.keys).forEach { kind ->
            val (depth, lag) = backlog[kind] ?: Backlog(0, ZERO)
            gauge(DEPTH, "kind" to kind).set(depth.toDouble())
            gauge(LAG, "kind" to kind).set(lag.toDouble(DurationUnit.SECONDS))
        }
    }

    /** Claims what each kind has room for, and the jobs of kinds nothing here handles, and runs them. */
    internal fun claimAndRun(): Int {
        val claimed = handlers.byName.values.flatMap { handled ->
            val room = handled.kind.concurrency - running.getValue(handled.kind.name).get()
            if (room <= 0) {
                emptyList()
            } else {
                queue.claim(room, lease(handled.kind.deadline), Kinds.Only(handled.kind), settings.perTenant)
            }
        } + queue.claim(UNHANDLED_BATCH, lease(ZERO), Kinds.Except(handlers.byName.keys), settings.perTenant)
        claimed.forEach(::dispatch)
        return claimed.size
    }

    private fun dispatch(job: Claimed) {
        val count = running[job.kind]
        count?.incrementAndGet()
        val future = surefoldFuture {
            try {
                run.run(job)
            } catch (failed: SQLException) {
                logWarn("surefold-jobs: job ${job.id} ran, but its result could not be written: $failed")
            } finally {
                count?.decrementAndGet()
            }
        }
        inFlight += future
        // Runs at once if the job has already finished, so nothing is left in the set.
        future.whenComplete { _, _ -> inFlight -= future }
    }

    private fun lease(deadline: Duration) = deadline + settings.leaseMargin

    /** Stops claiming, waits up to [JobSettings.drain] for runs in flight, and interrupts what is left. */
    override fun close() {
        lock.withLock {
            if (stopping) return
            stopping = true
            sleeping?.interrupt()
        }
        listener?.close()
        loop?.join()
        val until = System.nanoTime() + settings.drain.inWholeNanoseconds
        // Through the iterator: `toList` reads the size first, and a run that ends in between makes it throw.
        inFlight.iterator().asSequence().toList().forEach { future ->
            try {
                future.get(maxOf(0, until - System.nanoTime()), TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                future.cancel(true)
            } catch (_: ExecutionException) {
                // A run's own failures are written as its job's result; anything else was logged where it ran.
            } catch (_: CancellationException) {
                // Cancelled by an earlier pass of this loop.
            }
        }
        logInfo("surefold-jobs: worker $name stopped")
    }

    private companion object {
        const val UNHANDLED_BATCH = 10

        /** Jobs due and waiting, per kind. */
        const val DEPTH = "surefold.jobs.depth"

        /** How long the oldest job due has waited past its run time, in seconds, per kind. */
        const val LAG = "surefold.jobs.lag.seconds"
    }
}
