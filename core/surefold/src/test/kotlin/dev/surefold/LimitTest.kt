package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.withClue
import dev.surefold.fail.attempt
import dev.surefold.fail.left
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.PriorityQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** A limit on calls into one dependency: a fixed one never lets more in, and an adaptive one finds its level. */
class LimitTest {

    /**
     * A dependency that serves [capacity] calls at once in [baseNanos]; beyond that, calls queue, and each takes
     * longer in proportion. Drives [vegas] with one call at the current limit per step, for [steps] steps.
     */
    private fun settle(vegas: Vegas, start: Int, capacity: () -> Int, steps: Int): Int {
        var limit = start
        repeat(steps) {
            val took = (BASE_NANOS * maxOf(1.0, limit.toDouble() / capacity())).toLong()
            limit = vegas.sample(took, inFlight = limit, dropped = false)
        }
        return limit
    }

    /** 10 ms a call, give or take a tenth: exactly 10 would finish every call in lockstep, as no dependency does. */
    private fun steady(): () -> Long {
        val random = Random(SEED)
        return { (BASE_NANOS * (0.9 + random.nextDouble() / 5)).toLong() }
    }

    /**
     * A dependency that serves [capacity] calls at once, each taking what [service] says, with calls beyond that
     * waiting their turn in its queue; callers keep [vegas]'s limit full. Time is simulated, one completion to the
     * next, so a call that queued under an earlier, higher limit is measured late, as it is in a real service.
     */
    private class Dependency(private val vegas: Vegas, var capacity: Int, private val service: () -> Long) {
        private class Call(val arrived: Long, val found: Int, val done: Long)

        private val serving = PriorityQueue<Call>(compareBy { it.done })
        private val waiting = ArrayDeque<Pair<Long, Int>>()
        var now = 0L
        var limit = 0
        var completed = 0L
        var tookTotal = 0L
        var lowestLimit = Int.MAX_VALUE

        fun start(limit: Int) = apply {
            this.limit = limit
            fill()
        }

        /** Runs for [nanos] of simulated time, calling [watch] with the limit after each completion. */
        fun runFor(nanos: Long, watch: (Int) -> Unit = {}) {
            val until = now + nanos
            while (serving.peek().done <= until) {
                val call = serving.poll()
                now = call.done
                limit = vegas.sample(now - call.arrived, call.found, dropped = false)
                completed++
                tookTotal += now - call.arrived
                lowestLimit = minOf(lowestLimit, limit)
                watch(limit)
                admit()
                fill()
            }
            now = until
        }

        /** Calls answered per second and their mean latency in ms, over [nanos] from now. */
        fun measure(nanos: Long): Pair<Double, Double> {
            val (calls, took) = completed to tookTotal
            runFor(nanos)
            val answered = completed - calls
            return answered * 1e9 / nanos to (tookTotal - took) / 1e6 / answered
        }

        private fun fill() {
            while (serving.size + waiting.size < limit) {
                waiting.addLast(now to serving.size + waiting.size + 1)
                admit()
            }
        }

        private fun admit() {
            while (serving.size < capacity && waiting.isNotEmpty()) {
                val (arrived, found) = waiting.removeFirst()
                serving.add(Call(arrived, found, now + service()))
            }
        }
    }

    @Test
    fun `an adaptive limit climbs to what the dependency can take, and stops a little above it`() {
        val limit = settle(Vegas(initial = 20, min = 1, max = 5_000), 20, capacity = { 400 }, steps = 50_000)

        withClue("settled at $limit for a capacity of 400") { (limit in 400..460) shouldBe true }
    }

    @Test
    fun `an adaptive limit follows the dependency down within a few windows when its capacity halves`() {
        val vegas = Vegas(initial = 20, min = 1, max = 5_000)
        var capacity = 400
        val before = settle(vegas, 20, { capacity }, steps = 50_000)
        capacity = 200

        val limit = settle(vegas, before, { capacity }, steps = 2_000)

        withClue("settled at $limit for a capacity of 200") { (limit in 200..230) shouldBe true }
    }

    @Test
    fun `an adaptive limit reaches a steady dependency's capacity within a few windows, and serves it in full`() {
        val dependency = Dependency(Vegas(initial = 20, min = 1, max = 5_000), capacity = 500, steady()).start(20)
        var reached = Long.MAX_VALUE
        dependency.runFor(SECOND) { if (it >= 500 && reached == Long.MAX_VALUE) reached = dependency.now }

        val (rate, latency) = dependency.measure(5 * SECOND)

        withClue("reached 500 after ${reached / 1e6} ms") { (reached <= 10 * BASE_NANOS) shouldBe true }
        withClue("served $rate a second of 50,000") { (rate >= 49_000) shouldBe true }
        withClue("mean latency $latency ms over 10 unloaded") { (latency <= 12.0) shouldBe true }
    }

    @Test
    fun `an adaptive limit follows a dependency that loses capacity without cutting below it`() {
        val dependency = Dependency(Vegas(initial = 20, min = 1, max = 5_000), capacity = 500, steady()).start(20)
        dependency.runFor(2 * SECOND)
        dependency.capacity = 100
        dependency.lowestLimit = Int.MAX_VALUE
        var followed = Long.MAX_VALUE
        val dropped = dependency.now
        dependency.runFor(SECOND) { if (it <= 130 && followed == Long.MAX_VALUE) followed = dependency.now - dropped }

        val (rate, latency) = dependency.measure(5 * SECOND)

        withClue("down to 130 after ${followed / 1e6} ms") { (followed <= 20 * BASE_NANOS) shouldBe true }
        withClue("lowest limit after the drop") { (dependency.lowestLimit >= 95) shouldBe true }
        withClue("served $rate a second of 10,000") { (rate >= 9_800) shouldBe true }
        withClue("mean latency $latency ms over 10 unloaded") { (latency <= 12.0) shouldBe true }
    }

    @Test
    fun `an adaptive limit climbs after a dependency that gains capacity`() {
        val dependency = Dependency(Vegas(initial = 20, min = 1, max = 5_000), capacity = 100, steady()).start(20)
        dependency.runFor(2 * SECOND)
        dependency.capacity = 400
        dependency.runFor(SECOND)

        val (rate, _) = dependency.measure(5 * SECOND)

        withClue("limit ${dependency.limit} for a capacity of 400") { (dependency.limit in 400..480) shouldBe true }
        withClue("served $rate a second of 40,000") { (rate >= 39_000) shouldBe true }
    }

    @Test
    fun `an adaptive limit a little over a steady dependency's capacity does not creep up`() {
        val dependency = Dependency(Vegas(initial = 20, min = 1, max = 5_000), capacity = 400, steady()).start(20)
        dependency.runFor(30 * SECOND)
        var most = 0
        dependency.runFor(10 * SECOND) { most = maxOf(most, it) }

        val (_, latency) = dependency.measure(5 * SECOND)

        withClue("highest limit after 30 s for a capacity of 400") { (most <= 520) shouldBe true }
        withClue("mean latency $latency ms over 10 unloaded") { (latency <= 12.0) shouldBe true }
    }

    @Test
    fun `an adaptive limit comes back to a dependency that got slower for good`() {
        var slower = 1L
        val jitter = steady()
        val dependency = Dependency(Vegas(initial = 20, min = 1, max = 5_000), capacity = 400) { slower * jitter() }
        dependency.start(20).runFor(2 * SECOND)
        slower = 2
        dependency.runFor(10 * SECOND)

        val (rate, _) = dependency.measure(5 * SECOND)

        withClue("limit ${dependency.limit} for a capacity of 400") { (dependency.limit >= 400) shouldBe true }
        withClue("served $rate a second of 20,000") { (rate >= 19_000) shouldBe true }
    }

    @Test
    fun `an adaptive limit keeps a dependency with noisy latency busy without queueing much`() {
        val random = Random(SEED)
        val dependency = Dependency(Vegas(initial = 20, min = 1, max = 5_000), capacity = 400) {
            // Each call within a quarter of 10 ms either way, and one in a hundred four times as long.
            val jitter = BASE_NANOS * (0.75 + random.nextDouble() / 2)
            (if (random.nextInt(100) == 0) 4 * jitter else jitter).toLong()
        }.start(20)
        dependency.runFor(2 * SECOND)

        val (rate, latency) = dependency.measure(5 * SECOND)

        val capacity = 400 / (BASE_NANOS * 1.03 / 1e9)
        withClue("served $rate a second of $capacity") { (rate >= 0.95 * capacity) shouldBe true }
        withClue("mean latency $latency ms over 10.3 unloaded") { (latency <= 13.0) shouldBe true }
    }

    @Test
    fun `an adaptive limit that is barely used does not grow`() {
        val vegas = Vegas(initial = 20, min = 1, max = 5_000)
        var limit = 20
        repeat(1_000) { limit = vegas.sample(BASE_NANOS, inFlight = 2, dropped = false) }

        limit shouldBe 20
    }

    @Test
    fun `a failed call backs the limit off at once`() {
        val vegas = Vegas(initial = 100, min = 1, max = 1_000)

        vegas.sample(BASE_NANOS, inFlight = 100, dropped = true) shouldBe 90
    }

    @Test
    fun `an adaptive limit never leaves its bounds`() {
        val vegas = Vegas(initial = 10, min = 5, max = 50)

        settle(vegas, 10, capacity = { 1_000 }, steps = 5_000) shouldBe 50
        repeat(100) { vegas.sample(BASE_NANOS, inFlight = 50, dropped = true) }
        vegas.sample(BASE_NANOS, inFlight = 5, dropped = true) shouldBe 5
    }

    @Test
    fun `a fixed limit never lets more than its number in at once`() {
        val limit = Limit.fixed(4)
        val now = AtomicInteger()
        val most = AtomicInteger()
        Executors.newVirtualThreadPerTaskExecutor().use { threads ->
            repeat(200) {
                threads.submit {
                    limit.withPermit {
                        most.accumulateAndGet(now.incrementAndGet(), ::maxOf)
                        Thread.sleep(1)
                        now.decrementAndGet()
                    }
                }
            }
        }

        withClue("the most calls ever inside at once") { (most.get() <= 4) shouldBe true }
        limit.inFlight shouldBe 0
    }

    @Test
    fun `a call waits for room, and gets it when another leaves`() {
        val limit = Limit.fixed(1)
        val inside = CountDownLatch(1)
        val leave = CountDownLatch(1)
        val first = Thread.ofVirtual().start { limit.withPermit { inside.countDown(); leave.await() } }
        inside.await()
        val second = Thread.ofVirtual().start { limit.withPermit { } }

        withClue("the second call is still waiting") { second.join(Duration.ofMillis(WAIT_MILLIS)) shouldBe false }
        leave.countDown()

        first.join(Duration.ofSeconds(SETTLE_SECONDS)) shouldBe true
        second.join(Duration.ofSeconds(SETTLE_SECONDS)) shouldBe true
    }

    @Test
    fun `a call that fails backs an adaptive limit off, and one that is cancelled does not`() {
        val limit = Limit.adaptive("db", initial = 100, max = 1_000)

        try {
            limit.withPermit { error("connection reset") }
        } catch (_: IllegalStateException) {
            // The failure the limit learns from.
        }
        limit.current shouldBe 90

        try {
            limit.withPermit { throw CancellationException("raised") }
        } catch (_: CancellationException) {
            // A declared failure, which says nothing about the dependency.
        }
        limit.current shouldBe 90
        limit.inFlight shouldBe 0
    }

    @Test
    fun `a fail passing through a permit is the call's answer, not a failure the limit learns from`() {
        val limit = Limit.adaptive("db", initial = 100, max = 1_000)

        val answered = attempt<String, Int> { limit.withPermit { fail("not found") } }

        answered shouldBe "not found".left()
        limit.current shouldBe 100
        limit.inFlight shouldBe 0
    }

    @Test
    fun `an adaptive limit publishes what it allows and what is inside it`() {
        capturingMetrics { captured ->
            Limit.adaptive("orders-db", initial = 10, max = 100).withPermit { }

            captured.gauge("surefold.limit.current") shouldBe 10.0
            captured.gauge("surefold.limit.in_use") shouldBe 0.0
        }
    }

    private companion object {
        const val BASE_NANOS = 10_000_000L
        const val SECOND = 1_000_000_000L
        const val SEED = 171L
        const val SETTLE_SECONDS = 10L
        const val WAIT_MILLIS = 50L
    }
}
