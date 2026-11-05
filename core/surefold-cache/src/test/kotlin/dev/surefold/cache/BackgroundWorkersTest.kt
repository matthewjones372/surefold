package dev.surefold.cache

import dev.surefold.Clock
import dev.surefold.Counter
import dev.surefold.Histogram
import dev.surefold.Metrics
import dev.surefold.NoMetrics
import dev.surefold.Promise
import dev.surefold.Schedule
import dev.surefold.Shared
import dev.surefold.TestClock
import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldThrow
import dev.surefold.awaitValue
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.metrics
import dev.surefold.surefoldFuture
import dev.surefold.surefoldLocal
import dev.surefold.test.eventually
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(15)
class BackgroundWorkersTest {
    @Test
    fun `ordinary close interrupts displaced finite sleepers before their resistant cleanup finishes`() {
        val sleeps = List(2) { Sleeper() }
        val calls = Shared(0)
        val moving =
            object : Clock {
                override fun now(): Instant = Instant.EPOCH

                override fun sleep(duration: Duration) {
                    val index = calls.modify { it + 1 to it }
                    sleeps[index].sleep()
                }
            }
        val prices =
            clock.locally(moving) {
                Cache<String, Nothing, Int>({ 1.minutes }, refreshing = Schedule.spaced(20.seconds)) { Either.Right(7) }
            }
        try {
            prices.get("sku") shouldBe Either.Right(7)
            sleeps[0].started.awaitValue(3.seconds) shouldBe Unit
            prices.refresh("sku")
            sleeps[1].started.awaitValue(3.seconds) shouldBe Unit
            val closing = surefoldFuture { prices.close() }
            closing.get(3, TimeUnit.SECONDS)
            sleeps.forEach { it.interrupted.awaitValue(3.seconds) shouldBe Unit }
            sleeps.forEach { it.finished.peek() shouldBe null }
        } finally {
            sleeps.forEach { it.release.succeed(Unit) }
            prices.close()
            sleeps.forEach { it.finished.awaitValue(3.seconds) shouldBe Unit }
        }
    }

    @Test
    fun `close cancellation reaches accepted workers before submission and before registration returns`() {
        listOf(true, false).forEach { beforeSubmission ->
            val pause = StartupPause(beforeSubmission)
            val lookups = Shared(0)
            val prices =
                Cache<String, Nothing, Int>({ 1.minutes }) {
                    lookups.update { count -> count + 1 }
                    Either.Right(7)
                }
            val submitting = metrics.locally(pause) { surefoldFuture { prices.refresh("sku") } }
            try {
                val boundary = if (beforeSubmission) pause.accepted else pause.registered
                boundary.awaitValue(3.seconds)?.isAlive shouldBe true
                surefoldFuture { prices.close() }.get(3, TimeUnit.SECONDS)
                pause.release.succeed(Unit)
                submitting.get(3, TimeUnit.SECONDS)
                val worker = checkNotNull(pause.registered.awaitValue(3.seconds))
                eventually(3.seconds) { worker.isAlive shouldBe false }
                lookups.peek() shouldBe 0
            } finally {
                pause.release.succeed(Unit)
                prices.close()
                submitting.get(3, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `a zero-delay sleeper is registered before it refreshes the value read in its window`() {
        val started = Promise<Nothing, Unit>()
        val release = Promise<Nothing, Unit>()
        val lookups = Shared(0)
        val moving =
            object : Clock {
                override fun now(): Instant = Instant.EPOCH

                override fun sleep(duration: Duration) {
                    duration shouldBe Duration.ZERO
                    started.succeed(Unit)
                    release.awaitValue()
                }
            }
        val prices =
            clock.locally(moving) {
                Cache<String, Nothing, Int>({ 1.minutes }, refreshing = Schedule.recurs(1)) {
                    Either.Right(lookups.modify { count -> count + 1 to count + 1 })
                }
            }
        try {
            prices.get("sku") shouldBe Either.Right(1)
            started.awaitValue(3.seconds) shouldBe Unit
            prices.get("sku") shouldBe Either.Right(1)
            release.succeed(Unit)
            eventually(3.seconds) {
                prices.get("sku") shouldBe Either.Right(2)
                prices.ahead() shouldBe 0
            }
            lookups.peek() shouldBe 2
        } finally {
            release.succeed(Unit)
            prices.close()
        }
    }

    @Test
    fun `an explicit refresh inherits its caller bindings and cancelling one waiter leaves its lookup running`() {
        val bound = surefoldLocal { "unbound" }
        val cacheClock = TestClock()
        val callerClock = TestClock()
        val started = Promise<Nothing, Unit>()
        val release = Promise<Nothing, Unit>()
        val interrupted = Shared(false)
        val prices =
            clock.locally(cacheClock) {
                Cache<String, Nothing, String>({ 1.minutes }) {
                    clock.get() shouldBe callerClock
                    started.succeed(Unit)
                    try {
                        release.awaitValue()
                    } catch (stop: InterruptedException) {
                        interrupted.update { true }
                        throw stop
                    }
                    Either.Right(bound.get())
                }
            }
        try {
            clock.locally(callerClock) { bound.locally("first") { prices.refresh("sku") } }
            started.awaitValue(3.seconds) shouldBe Unit
            val waiter = bound.locally("second") { surefoldFuture { prices.get("sku") } }
            eventually(3.seconds) { prices.waiting("sku") shouldBe 2 }
            waiter.cancel(true)
            eventually(3.seconds) { prices.waiting("sku") shouldBe 1 }
            interrupted.peek() shouldBe false
            release.succeed(Unit)
            eventually(3.seconds) { prices.stats().size shouldBe 1 }
            callerClock.adjust(2.minutes)
            prices.get("sku") shouldBe Either.Right("first")
            prices.stats().loads shouldBe 1
        } finally {
            release.succeed(Unit)
            prices.close()
        }
    }

    @Test
    fun `failed admission metrics leave only the next live background owned`() {
        listOf(false, true).forEach { histogram ->
            val marker = IllegalStateException("admission metric failed")
            val broken = BrokenMetrics("surefold.cache.backgrounds", marker, histogram)
            val started = Promise<Nothing, Unit>()
            val release = Promise<Nothing, Unit>()
            val lookups = Shared(0)
            val prices =
                Cache<String, Nothing, Int>({ 1.minutes }) {
                    lookups.update { count -> count + 1 }
                    started.succeed(Unit)
                    release.awaitValue()
                    Either.Right(7)
                }
            try {
                repeat(3) {
                    metrics.locally(broken) {
                        shouldThrow<IllegalStateException> { prices.refresh("sku") } shouldBe marker
                    }
                }
                lookups.peek() shouldBe 0
                prices.refresh("sku")
                started.awaitValue(3.seconds) shouldBe Unit
                ownedBackgroundCount(prices) shouldBe 1
            } finally {
                release.succeed(Unit)
                prices.close()
            }
        }
    }

    @Test
    fun `ordinary close cancels every worker and flight when admission metrics fail`() {
        closeWithBrokenMetrics("surefold.cache.backgrounds")
    }

    @Test
    fun `ordinary close cancels every worker and flight when cancellation metrics fail`() {
        closeWithBrokenMetrics("unnamed")
    }

    @Test
    fun `ordinary close returns before an explicit refresh finishes resistant lookup cleanup`() {
        val started = Promise<Nothing, Unit>()
        val interrupted = Promise<Nothing, Unit>()
        val release = Promise<Nothing, Unit>()
        val finished = Promise<Nothing, Unit>()
        val prices =
            Cache<String, Nothing, Int>({ 1.minutes }) {
                started.succeed(Unit)
                try {
                    release.awaitValue()
                } catch (_: InterruptedException) {
                    interrupted.succeed(Unit)
                    awaitResisting(release)
                } finally {
                    finished.succeed(Unit)
                }
                Either.Right(7)
            }
        try {
            prices.refresh("sku")
            started.awaitValue(3.seconds) shouldBe Unit
            surefoldFuture { prices.close() }.get(3, TimeUnit.SECONDS)
            interrupted.awaitValue(3.seconds) shouldBe Unit
            finished.peek() shouldBe null
        } finally {
            release.succeed(Unit)
            prices.close()
            finished.awaitValue(3.seconds) shouldBe Unit
        }
    }
}

private class Sleeper {
    val started = Promise<Nothing, Unit>()
    val interrupted = Promise<Nothing, Unit>()
    val release = Promise<Nothing, Unit>()
    val finished = Promise<Nothing, Unit>()

    fun sleep() {
        started.succeed(Unit)
        try {
            release.awaitValue()
        } catch (_: InterruptedException) {
            interrupted.succeed(Unit)
            awaitResisting(release)
        } finally {
            finished.succeed(Unit)
        }
    }
}

private fun awaitResisting(release: Promise<Nothing, Unit>) {
    while (release.peek() == null) {
        try {
            release.awaitValue()
        } catch (_: InterruptedException) {
            // Closing remains interrupt-only while cleanup is still using its dependency.
        }
    }
}

private class StartupPause(private val beforeSubmission: Boolean) : Metrics by NoMetrics {
    val accepted = Promise<Nothing, Thread>()
    val registered = Promise<Nothing, Thread>()
    val release = Promise<Nothing, Unit>()

    override fun counter(name: String, tags: Map<String, String>): Counter = Counter {
        if (name == "surefold.transact.runs") {
            metrics.locally(NoMetrics) {
                when (tags["transact"]) {
                    "surefold.cache.backgrounds" -> {
                        accepted.succeed(Thread.currentThread())
                        if (beforeSubmission) awaitResisting(release)
                    }

                    "promise" -> {
                        registered.succeed(Thread.currentThread())
                        if (!beforeSubmission) awaitResisting(release)
                    }
                }
            }
        }
    }
}

private class BrokenMetrics(
    private val transaction: String,
    private val marker: IllegalStateException,
    private val histogram: Boolean = false,
) : Metrics by NoMetrics {
    override fun counter(name: String, tags: Map<String, String>): Counter = Counter {
        if (!histogram && name == "surefold.transact.runs" && tags["transact"] == transaction) throw marker
    }

    override fun histogram(name: String, tags: Map<String, String>): Histogram = Histogram {
        if (histogram && name == "surefold.transact.attempts" && tags["transact"] == transaction) throw marker
    }
}

private fun ownedBackgroundCount(cache: Cache<*, *, *>): Int {
    val state = Cache::class.java.getDeclaredField("work").apply { isAccessible = true }.get(cache) as Shared<*>
    val work = checkNotNull(state.peek())
    val workers = work.javaClass.getDeclaredField("workers").apply { isAccessible = true }.get(work) as List<*>
    return workers.size
}

private fun closeWithBrokenMetrics(transaction: String) {
    val sleepers = List(2) { Sleeper() }
    val lookup = Sleeper()
    val calls = Shared(0)
    val moving =
        object : Clock {
            override fun now(): Instant = Instant.EPOCH

            override fun sleep(duration: Duration) {
                sleepers[calls.modify { count -> count + 1 to count }].sleep()
            }
        }
    val prices =
        clock.locally(moving) {
            Cache<String, Nothing, Int>({ 1.minutes }, refreshing = Schedule.spaced(20.seconds)) { key ->
                if (key == "flight") lookup.sleep()
                Either.Right(7)
            }
        }
    val marker = IllegalStateException("close metric failed")
    try {
        prices.get("a")
        prices.get("b")
        sleepers.forEach { it.started.awaitValue(3.seconds) shouldBe Unit }
        prices.refresh("flight")
        lookup.started.awaitValue(3.seconds) shouldBe Unit
        metrics.locally(BrokenMetrics(transaction, marker)) {
            shouldThrow<IllegalStateException> { prices.close() } shouldBe marker
        }
        (sleepers + lookup).forEach {
            it.interrupted.awaitValue(3.seconds) shouldBe Unit
            it.finished.peek() shouldBe null
        }
    } finally {
        (sleepers + lookup).forEach { it.release.succeed(Unit) }
        prices.close()
        (sleepers + lookup).forEach { it.finished.awaitValue(3.seconds) shouldBe Unit }
    }
}
