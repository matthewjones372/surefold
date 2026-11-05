package dev.surefold.cache

import dev.surefold.ExitCase
import dev.surefold.NoMetrics
import dev.surefold.Promise
import dev.surefold.Schedule
import dev.surefold.ScheduleStep
import dev.surefold.Shared
import dev.surefold.SingleFlight
import dev.surefold.awaitValue
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.logWarn
import dev.surefold.metrics
import dev.surefold.resourceScope
import dev.surefold.surefoldFuture
import dev.surefold.surefoldLocal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** What a cache is told when a value may have changed: one key, or everything, when a change may have been missed. */
sealed interface Invalidation<out K> {
    data class Key<K>(val key: K) : Invalidation<K>

    data object All : Invalidation<Nothing>
}

/** What a cache has done since it was made: each `get` is a hit or a miss, and each miss a load. */
data class CacheStats(
    val hits: Long,
    val misses: Long,
    val loads: Long,
    val failures: Long,
    val stale: Long,
    val evictions: Long,
    val layerHits: Long,
    val layerFailures: Long,
    val size: Int,
)

/**
 * A function with a memory (spec 0246): [lookup] is given once, here, and [get] answers from memory while an answer is
 * young, and looks up again once it is not. However many ask for one key at once, one lookup runs, through
 * `SingleFlight`. A zero [ttl] shares the answer without keeping it or writing layers; an infinite [ttl] keeps a fresh
 * result only in memory without a deadline. Other answers are kept as long as [ttl] says, a `Left` included, so a
 * source that is down is asked once per its `Left`'s ttl, not once per caller. When a lookup fails and the last `Right`
 * for the key is younger than [stale], that `Right` answers instead, until [stale] runs out. Ages are read from the
 * clock bound where the cache is made, so a `TestClock` moves every one.
 *
 * At most [capacity] keys are kept. Past it, a new key is kept only if it has been asked for more often lately than the
 * least recently read key it would push out (TinyLFU), so a scan of keys read once cannot flush the keys read all day.
 * [onEvict] is handed each value no entry holds any more: pushed out, invalidated, or replaced by a newer one.
 *
 * Under its own memory, a cache asks [layers] in turn before it looks up: a hit in one is written into its own memory
 * and every nearer layer, keeping its expiry, so a value is never younger near than it was far. A good value looked up
 * with a finite ttl is written into every layer; a failure is kept in its own memory only.
 *
 * [retry] runs a failing lookup again inside one lookup, after the delays it answers on the cache's clock, before its
 * failure is kept; every caller waiting on that key shares the retries. [refreshing] looks a key up again ahead of its
 * expiry: after each lookup it is stepped with the result, and its delay is when the key is looked up again in the
 * background, for as long as someone has read it since the last time; a key nobody read is left to expire. A cache is
 * closed when its owner is done with it, which stops every refresh and every lookup in flight.
 */
class Cache<K : Any, E, V>(
    private val ttl: (Either<E, V>) -> Duration,
    private val stale: Duration = Duration.ZERO,
    capacity: Int = DEFAULT_CAPACITY,
    private val onEvict: (K, V) -> Unit = { _, _ -> },
    private val layers: List<Layer<K, V>> = emptyList(),
    private val retry: Schedule<E, *>? = null,
    private val refreshing: Schedule<Either<E, V>, *>? = null,
    private val lookup: (K) -> Either<E, V>,
) : AutoCloseable {
    init {
        require(!stale.isNegative()) { "a stale value cannot be kept for $stale" }
    }

    // A key's answer, until when, and the last good value behind it, kept to answer for it while lookups fail.
    private class Entry<E, V>(val answer: Either<E, V>, val until: Instant?, val good: Good<V>?, val isStale: Boolean) {
        fun young(now: Instant): Boolean = until == null || now < until
    }

    private class Good<V>(val value: V, val at: Instant)

    private val time = clock.get()
    private val evictions = AtomicLong()
    private val entries = Memory<K, Entry<E, V>>(capacity)
    private val flights = SingleFlight<K, E, V>("surefold.cache")

    // Every invalidation suppresses older publication phases that have not been admitted.
    private val admission = ReentrantLock()
    private var generation = 0L
    private var stopped = false
    private var closing: Promise<Throwable, Unit>? = null
    private val owners = surefoldLocal<List<() -> Boolean>> { emptyList() }
    private var operations = emptyList<Operation<K>>()
    private val layerCall = surefoldLocal<Promise<Nothing, Unit>?> { null }

    private class Operation<K>(val what: Invalidation<K>?) {
        val thread: Thread = Thread.currentThread()
        val released = Promise<Nothing, Unit>()
        val finished = Promise<Nothing, Unit>()
    }

    private val hits = AtomicLong()
    private val misses = AtomicLong()
    private val loads = AtomicLong()
    private val failures = AtomicLong()
    private val staleAnswers = AtomicLong()
    private val layerHits = AtomicLong()
    private val layerFailures = AtomicLong()

    // Each key refreshed ahead: the schedule's next step, whether it was read since its last lookup, and its sleeper.
    private class Ahead<E, V>(val next: ScheduleStep<Either<E, V>, *>) {
        @Volatile var read = false

        @Volatile var sleeper: Background? = null
    }

    private val ahead = ConcurrentHashMap<K, Ahead<E, V>>()

    private class Background {
        private val runner = Promise<Nothing, Thread?>()
        private val cancelled = Shared(false)

        fun run(block: () -> Unit) {
            runner.succeed(Thread.currentThread())
            if (!cancelled.peek()) block()
        }

        fun cancel() {
            try {
                cancelled.update { true }
            } finally {
                (runner.peek() as? Either.Right)?.value?.interrupt()
            }
        }

        fun abandoned() {
            runner.succeed(null)
        }

        fun join() {
            runner.awaitValue()?.join()
        }

        // Keep the actual thread after its body answers: future completion is not termination.
        fun ended(): Boolean {
            val started = runner.peek() as? Either.Right ?: return false
            return started.value?.isAlive != true
        }
    }

    private data class Work(val closed: Boolean = false, val workers: List<Background> = emptyList())

    private val work = Shared(Work(), "surefold.cache.backgrounds")
    private val closed: Boolean
        get() = work.peek().closed

    /** [key]'s answer: from memory while it is young, else from one [lookup] however many ask. */
    fun get(key: K): Either<E, V> = borrowed { mine ->
        val held = entries.read(key)
        if (held != null && held.young(time.now())) {
            ahead[key]?.read = true
            hits.incrementAndGet()
            if (held.isStale) staleAnswers.incrementAndGet()
            return@borrowed held.answer
        }
        misses.incrementAndGet()
        handoff(mine)
        flights.run(key) {
            ownWorker()
            load(key)
        }
    }

    /** Looks [key] up again in the background now; until it answers, and if it fails, `get` answers what it has. */
    fun refresh(key: K): Unit = borrowed {
        background {
            flights.run(key) {
                ownWorker()
                load(key, refreshing = true)
            }
        }
    }

    /** Stops every refresh ahead and every lookup in flight; a `get` after this is refused. */
    override fun close() {
        // Instrumentation can throw after a state commit; every cancellation still has to be attempted.
        resourceScope {
            onRelease { flights.cancelAll() }
            onRelease { ahead.clear() }
            onRelease { cancelBackgrounds() }
            admission.withLock { stopped = true }
            work.update { it.copy(closed = true) }
        }
    }

    /** Closes admission, joins owned work and clears memory, finishing release even if its caller is interrupted. */
    fun closeAndJoin() {
        check(owners.get().none { it() }) { "Owned cache work cannot close its owner" }
        val mine = Promise<Throwable, Unit>()
        val (completion, accepted) =
            admission.withLock {
                closing?.let { it to null }
                    ?: run {
                        closing = mine
                        stopped = true
                        mine to operations
                    }
            }
        if (accepted != null) {
            owners.locally(owners.get() + { mine.peek() == null }) { release(mine, accepted) }
        }
        when (val ended = finishing { completion.await() }) {
            is Either.Right -> Unit
            is Either.Left -> throw ended.value
        }
    }

    @Suppress("TooGenericExceptionCaught") // Release defects are published once to every owner waiting for completion.
    private fun release(completion: Promise<Throwable, Unit>, accepted: List<Operation<K>>) {
        var interrupted = Thread.interrupted()
        fun remember() {
            if (Thread.interrupted()) interrupted = true
        }
        fun cleanup(action: () -> Unit) {
            remember()
            try {
                action()
            } catch (stop: InterruptedException) {
                interrupted = true
                throw stop
            } finally {
                remember()
            }
        }
        try {
            try {
                resourceScope {
                    onRelease {
                        notify(entries.clear().map { (key, gone) -> { cleanup(eviction(key, gone)) } })
                    }
                    onRelease { cleanup { accepted.forEach { finishing { it.finished.awaitValue() } } } }
                    onRelease { cleanup { work.peek().workers.forEach { finishing { it.join() } } } }
                    onRelease { cleanup { flights.closeAndJoin() } }
                    onRelease { cleanup { cancelOperations(accepted) } }
                    finishing { work.update { it.copy(closed = true) } }
                    cleanup { close() }
                }
                finishing { completion.succeed(Unit) }
            } catch (thrown: Throwable) {
                finishing { completion.fail(thrown) }
            }
        } finally {
            remember()
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun cancelOperations(accepted: List<Operation<K>>) = resourceScope {
        accepted.forEach { mine ->
            onRelease {
                admission.withLock {
                    if (mine in operations && mine.finished.peek() == null) mine.thread.interrupt()
                }
            }
        }
    }

    private fun ownWorker() {
        val thread = Thread.currentThread()
        // SingleFlight's outer bindings include terminal publication after load returns.
        owners.attach(owners.get().filter { it() } + { thread.isAlive })
    }

    private fun <A> owned(mine: Operation<K>, block: () -> A): A =
        owners.locally(owners.get() + { mine.finished.peek() == null }, block)

    private fun <A> borrowed(block: (Operation<K>) -> A): A {
        checkLayer()
        val mine = Operation<K>(null)
        admission.withLock {
            check(closing == null && !closed) { "the cache has closed" }
            operations += mine
        }
        return owned(mine) {
            try {
                block(mine)
            } finally {
                handoff(mine)
            }
        }
    }

    private fun handoff(mine: Operation<K>) {
        finishing { mine.finished.succeed(Unit) }
        admission.withLock { operations -= mine }
    }

    // How many keys are refreshed ahead now, for a test that waits for a key to be let go.
    internal fun ahead(): Int = ahead.size

    /**
     * Forgets [what] in every layer, farthest first so a nearer one is not refilled from a stale copy, and then in its
     * own memory. The next `get` for it looks up again; a lookup in flight answers its callers, unkept.
     */
    fun invalidate(what: Invalidation<K>) {
        checkLayer()
        invalidateLayers(what)
    }

    private fun invalidateLayers(what: Invalidation<K>) =
        phase(what, after = ::notify) {
            layers.asReversed().forEach { layer ->
                when (what) {
                    is Invalidation.Key -> layered("remove ${what.key}") { layer.remove(what.key) }
                    Invalidation.All -> layered("clear") { layer.clear() }
                }
            }
            when (what) {
                is Invalidation.Key -> listOfNotNull(entries.remove(what.key)?.let { eviction(what.key, it) })
                Invalidation.All -> entries.clear().map { (key, gone) -> eviction(key, gone) }
            }
        }

    // How many callers wait on [key]'s lookup now, for a test that must let it end only once all have joined it.
    internal fun waiting(key: K): Int = flights.waiting(key)

    fun stats(): CacheStats =
        CacheStats(
            hits.get(),
            misses.get(),
            loads.get(),
            failures.get(),
            staleAnswers.get(),
            evictions.get(),
            layerHits.get(),
            layerFailures.get(),
            entries.size,
        )

    private fun load(key: K, refreshing: Boolean = false): Either<E, V> {
        val began = admission.withLock { generation }
        if (!refreshing)
            fromLayers(key, began)?.let {
                return it
            }
        val result = retried(key)
        val now = time.now()
        val young = entries.peek(key)?.takeIf { refreshing && it.young(now) }
        val retention = ttl(result)
        if (result is Either.Left && young != null) {
            // A refresh that failed keeps what it was refreshing, which is still young.
            failures.incrementAndGet()
            if (retention == Duration.ZERO) {
                phase(Invalidation.Key(key), began) { stopAhead(key) }
            } else {
                ahead(key, result, began)
            }
            return young.answer
        }
        val entry =
            when (result) {
                is Either.Right -> Entry<E, V>(result, deadline(now, retention), Good(result.value, now), false)
                is Either.Left -> failed(result, entries.peek(key)?.good, now, retention)
            }
        if (entry.isStale) staleAnswers.incrementAndGet()
        publish(key, result, entry, now, retention, began)
        return entry.answer
    }

    private fun publish(
        key: K,
        result: Either<E, V>,
        entry: Entry<E, V>,
        now: Instant,
        retention: Duration,
        began: Long,
    ) {
        if (retention == Duration.ZERO) {
            phase(Invalidation.Key(key), began, ::notify) {
                stopAhead(key)
                listOfNotNull(entries.remove(key)?.let { eviction(key, it) })
            }
            return
        }
        phase(Invalidation.Key(key), began, ::notify) { keep(key, entry) }
        if (result is Either.Right && entry.until != null) {
            val stored = Stored(result.value, now, entry.until)
            layers.forEach { layer ->
                phase(Invalidation.Key(key), began) { layered("write $key") { layer.write(key, stored) } }
            }
        }
        ahead(key, result, began)
    }

    // The lookup, run again on a failure for as long as [retry] carries on, sleeping on the cache's clock between.
    private fun retried(key: K): Either<E, V> {
        var result = lookupNow(key)
        var next = retry?.step ?: return result
        while (result is Either.Left) {
            checkLookup()
            when (val decision = next(result.value)) {
                is Schedule.Decision.Done -> return result

                is Schedule.Decision.Continue -> {
                    checkLookup()
                    time.sleep(decision.delay)
                    next = decision.step
                    result = lookupNow(key)
                }
            }
        }
        return result
    }

    private fun checkLookup() {
        if (admission.withLock { closing != null }) throw InterruptedException("the cache is releasing")
    }

    private fun lookupNow(key: K): Either<E, V> {
        checkLookup()
        loads.incrementAndGet()
        return lookup(key)
    }

    // Steps [refreshing] with [result], and sleeps until its delay to look [key] up again, if it was read meanwhile.
    private fun ahead(key: K, result: Either<E, V>, began: Long) {
        val schedule = refreshing ?: return
        val step = phase(Invalidation.Key(key), began) { ahead.remove(key)?.next ?: schedule.step } ?: return
        val decision = step(result) as? Schedule.Decision.Continue ?: return
        if (closed || decision.delay == Duration.INFINITE) return
        // In the map before its sleeper starts, so a sleeper with no delay finds itself there.
        phase(Invalidation.Key(key), began) {
            val mine = Ahead<E, V>(decision.step).also { ahead[key] = it }
            startAhead(key, mine, decision.delay)
        }
    }

    private fun startAhead(key: K, mine: Ahead<E, V>, delay: Duration) {
        mine.sleeper = background {
            time.sleep(delay)
            if (ahead[key] === mine && mine.read && !closed)
                flights.run(key) {
                    ownWorker()
                    load(key, refreshing = true)
                }
            ahead.remove(key, mine)
        }
        if (mine.sleeper == null) ahead.remove(key, mine)
    }

    private fun stopAhead(key: K) {
        ahead.remove(key)?.sleeper?.cancel()
    }

    private fun background(block: () -> Unit): Background? {
        val worker = Background()
        var submitted = false
        try {
            val accepted = work.modify {
                if (it.closed) {
                    it to false
                } else {
                    it.copy(workers = it.workers.filterNot { held -> held.ended() } + worker) to true
                }
            }
            if (!accepted) return null
            surefoldFuture {
                ownWorker()
                worker.run {
                    try {
                        block()
                    } finally {
                        // Close may interrupt this waiter before the lookup it starts registers with SingleFlight.
                        if (closed) flights.cancelAll()
                    }
                }
            }
            submitted = true
        } finally {
            if (!submitted) worker.abandoned()
        }
        return worker
    }

    private fun cancelBackgrounds() = resourceScope {
        work.peek().workers.forEach { worker -> onRelease { worker.cancel() } }
    }

    // The first layer holding a young value for [key], written into memory and every nearer layer on the way back.
    private fun fromLayers(key: K, began: Long): Either<E, V>? {
        val now = time.now()
        layers.forEachIndexed { at, layer ->
            val stored =
                phase(Invalidation.Key(key), began) { layered("read $key") { layer.read(key) } }
                    ?.takeIf { now < it.expiresAt } ?: return@forEachIndexed
            layerHits.incrementAndGet()
            val answer = Either.Right(stored.value)
            val retention = ttl(answer)
            if (admission.withLock { generation == began }) {
                if (retention == Duration.ZERO) {
                    phase(Invalidation.Key(key), began, ::notify) {
                        stopAhead(key)
                        listOfNotNull(entries.remove(key)?.let { eviction(key, it) })
                    }
                    return answer
                }
                val good = Good(stored.value, stored.storedAt)
                phase(Invalidation.Key(key), began, ::notify) {
                    keep(key, Entry(answer, stored.expiresAt, good, false))
                }
                layers.take(at).forEach { nearer ->
                    phase(Invalidation.Key(key), began) { layered("write $key") { nearer.write(key, stored) } }
                }
            }
            return answer
        }
        return null
    }

    private fun keep(key: K, entry: Entry<E, V>): List<() -> Unit> {
        val written = entries.write(key, entry)
        // A stale answer keeps the good value it answers with, so only a value nothing holds any more goes.
        return listOfNotNull(
            written.evicted?.let { (was, gone) -> eviction(was, gone) },
            written.replaced?.good?.takeIf { it !== entry.good }?.let { gone -> { onEvict(key, gone.value) } },
        )
    }

    private fun eviction(key: K, gone: Entry<E, V>): () -> Unit = {
        evictions.incrementAndGet()
        gone.good?.let { onEvict(key, it.value) }
    }

    private fun notify(callbacks: List<() -> Unit>) = resourceScope {
        callbacks.asReversed().forEach { callback -> onRelease { callback() } }
    }

    private fun <A> phase(what: Invalidation<K>, began: Long? = null, after: (A) -> Unit = {}, block: () -> A): A? {
        val mine = Operation(what)
        val before = admission.withLock {
            if (began != null && (stopped || generation != began)) return null
            if (began == null) {
                check(closing == null) { "the cache has closed" }
                generation++
            }
            operations.filter { it.released.peek() == null && conflicts(it.what, what) }.also { operations += mine }
        }
        return owned(mine) {
            resourceScope {
                onRelease { admission.withLock { operations -= mine } }
                onRelease { retire(mine, before, it) }
                quiet { before.forEach { it.released.awaitValue() } }
                val answer = block()
                // Notifications may synchronously reenter the key whose mutation has finished.
                finishing { mine.released.succeed(Unit) }
                after(answer)
                answer
            }
        }
    }

    private fun retire(mine: Operation<K>, before: List<Operation<K>>, exit: ExitCase) =
        finishing(exit is ExitCase.Cancelled) {
            before.forEach { it.released.awaitValue() }
            mine.released.succeed(Unit)
            mine.finished.succeed(Unit)
        }

    private fun <A> finishing(cancelled: Boolean = false, block: () -> A): A {
        var interrupted = Thread.interrupted() || cancelled
        try {
            while (true) {
                try {
                    return quiet(block)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun conflicts(left: Invalidation<K>?, right: Invalidation<K>): Boolean =
        left != null && (left == Invalidation.All || right == Invalidation.All || left == right)

    /** Private completion must survive a failing metrics sink. */
    private fun <A> quiet(block: () -> A): A = metrics.locally(NoMetrics, block)

    private fun checkLayer() {
        check(layerCall.get()?.let { it.peek() == null } != true) { "A layer cannot call its owning cache" }
    }

    private fun <A> layered(what: String, block: () -> A): A? = resourceScope {
        val lease = Promise<Nothing, Unit>()
        onRelease { finishing { lease.succeed(Unit) } }
        layerCall.locally(lease) { safely(what, block) }
    }

    @Suppress("TooGenericExceptionCaught") // a layer that fails is a miss, logged and counted, never the caller's error
    private inline fun <A> safely(what: String, block: () -> A): A? =
        try {
            block()
        } catch (failed: Exception) {
            layerFailures.incrementAndGet()
            logWarn("surefold-cache: a layer could not $what: $failed")
            null
        }

    // A failed lookup: the last good value while it is younger than [stale], kept no longer than either allows.
    private fun failed(result: Either<E, V>, good: Good<V>?, now: Instant, retention: Duration): Entry<E, V> {
        failures.incrementAndGet()
        val until = deadline(now, retention)
        val staleUntil = good?.let { it.at + stale.toJavaDuration() }
        return if (good != null && staleUntil != null && now < staleUntil) {
            Entry(Either.Right(good.value), until?.let { minOf(it, staleUntil) } ?: staleUntil, good, true)
        } else {
            Entry(result, until, null, false)
        }
    }

    private fun deadline(now: Instant, retention: Duration): Instant? =
        if (retention == Duration.INFINITE) null else now + retention.toJavaDuration()
}

/** How many keys a cache keeps unless it is told otherwise. */
const val DEFAULT_CAPACITY = 10_000
