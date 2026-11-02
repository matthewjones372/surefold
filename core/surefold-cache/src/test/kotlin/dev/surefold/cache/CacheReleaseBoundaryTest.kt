package dev.surefold.cache

import dev.surefold.Clock
import dev.surefold.Counter
import dev.surefold.Histogram
import dev.surefold.Metrics
import dev.surefold.NoMetrics
import dev.surefold.Promise
import dev.surefold.Schedule
import dev.surefold.Shared
import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldThrow
import dev.surefold.awaitValue
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.metrics
import dev.surefold.surefoldFuture
import dev.surefold.test.eventually
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Timeout(30)
class CacheReleaseBoundaryTest {
    @Test
    fun `terminal answer metrics reject self close before changing cache admission`() {
        listOf(false, true).forEach { histogram ->
            listOf(false, true).forEach { fails -> terminalSelfClose(histogram, fails) }
        }
    }

    @Test
    fun `background registration metrics reject self close before runner registration returns`() {
        val worker = Promise<Nothing, Thread>()
        val entered = Promise<Nothing, Unit>()
        val attempt = Promise<Nothing, Unit>()
        val finish = Promise<Nothing, Unit>()
        val interrupted = Promise<Nothing, Unit>()
        val selfAttempt = Promise<Nothing, CompletableFuture<IllegalStateException>>()
        val first = Shared(true)
        val cache = Cache<String, Nothing, Int>({ 1.minutes }) { Either.Right(1) }
        val instrumented = ReleaseMetrics("promise") {
            if (first.modify { false to it }) {
                worker.succeed(Thread.currentThread())
                attempt.releaseAwait()
                selfAttempt.succeed(surefoldFuture { shouldThrow<IllegalStateException> { cache.closeAndJoin() } })
                entered.succeed(Unit)
                try {
                    finish.awaitValue()
                } catch (_: InterruptedException) {
                    interrupted.succeed(Unit)
                    finish.releaseAwait()
                }
            }
        }
        metrics.locally(instrumented) { cache.refresh("a") }
        attempt.succeed(Unit)
        try {
            entered.awaitValue(3.seconds) shouldBe Unit
            selfAttempt.awaitValue(3.seconds)?.releaseBounded()
            cache.get("b") shouldBe Either.Right(1)
            val close = surefoldFuture { cache.closeAndJoin() }
            try {
                interrupted.awaitValue(3.seconds) shouldBe Unit
                close.isDone shouldBe false
            } finally {
                finish.succeed(Unit)
                close.releaseBounded()
            }
            worker.awaitValue().isAlive shouldBe false
        } finally {
            attempt.succeed(Unit)
            finish.succeed(Unit)
            selfAttempt.peek()?.let { if (it is Either.Right) it.value.releaseBounded() }
            cache.closeAndJoin()
        }
    }

    @Test
    fun `release owns an accepted background while its caller is paused before submission`() {
        val accepted = Promise<Nothing, Unit>()
        val finish = Promise<Nothing, Unit>()
        val calls = Shared(0)
        val cache = Cache<String, Nothing, Int>({ 1.minutes }) { calls.update { count -> count + 1 }; Either.Right(1) }
        val instrumented = ReleaseMetrics("surefold.cache.backgrounds") {
            accepted.succeed(Unit)
            finish.releaseAwait()
        }
        val submitting = metrics.locally(instrumented) { surefoldFuture { cache.refresh("a") } }
        accepted.awaitValue(3.seconds) shouldBe Unit
        val closer = Promise<Nothing, Thread>()
        val close = surefoldFuture { closer.succeed(Thread.currentThread()); cache.closeAndJoin() }
        try {
            eventually(3.seconds) { closer.awaitValue().state shouldBe Thread.State.WAITING }
            close.isDone shouldBe false
        } finally {
            finish.succeed(Unit)
            submitting.releaseBounded()
            close.releaseBounded()
        }
        calls.peek() shouldBe 0
        cache.stats().size shouldBe 0
    }

    @Test
    fun `inherited terminal context rejects while its worker lives and retires when the worker dies`() {
        val worker = Promise<Nothing, Thread>()
        val child = Promise<Nothing, CompletableFuture<Unit>>()
        val refused = Promise<Nothing, Unit>()
        val finishWorker = Promise<Nothing, Unit>()
        val afterWorker = Promise<Nothing, Unit>()
        val cache = Cache<String, Nothing, Int>({ 1.minutes }) {
            worker.succeed(Thread.currentThread())
            Either.Right(1)
        }
        val instrumented = ReleaseMetrics("surefold.cache") {
            if (Thread.currentThread() === worker.awaitValue()) {
                child.succeed(
                    surefoldFuture {
                        shouldThrow<IllegalStateException> { cache.closeAndJoin() }
                        refused.succeed(Unit)
                        afterWorker.awaitValue()
                        cache.closeAndJoin()
                    },
                )
                finishWorker.releaseAwait()
            }
        }
        val get = metrics.locally(instrumented) { surefoldFuture { cache.get("a") } }
        try {
            refused.awaitValue(3.seconds) shouldBe Unit
            cache.get("b") shouldBe Either.Right(1)
            finishWorker.succeed(Unit)
            eventually(3.seconds) { worker.awaitValue().isAlive shouldBe false }
            afterWorker.succeed(Unit)
            child.awaitValue(3.seconds)?.releaseBounded() shouldBe Unit
            get.releaseBounded() shouldBe Either.Right(1)
            cache.stats().size shouldBe 0
        } finally {
            finishWorker.succeed(Unit)
            afterWorker.succeed(Unit)
            cache.closeAndJoin()
        }
    }

    @Test
    fun `nested lookup preserves its still live worker ancestor in inherited context`() {
        val inner = Promise<Nothing, Thread>()
        val outerReady = Promise<Nothing, Unit>()
        val finishOuter = Promise<Nothing, Unit>()
        val tryChild = Promise<Nothing, Unit>()
        val child = Promise<Nothing, CompletableFuture<Unit>>()
        lateinit var cache: Cache<String, Nothing, Int>
        cache = Cache({ 1.minutes }) { key ->
            if (key == "outer") {
                cache.get("inner")
                outerReady.succeed(Unit)
                finishOuter.releaseAwait()
            } else {
                inner.succeed(Thread.currentThread())
                child.succeed(
                    surefoldFuture {
                        tryChild.awaitValue()
                        shouldThrow<IllegalStateException> { cache.closeAndJoin() }
                        Unit
                    },
                )
            }
            Either.Right(1)
        }
        val get = surefoldFuture { cache.get("outer") }
        try {
            outerReady.awaitValue(3.seconds) shouldBe Unit
            eventually(3.seconds) { inner.awaitValue().isAlive shouldBe false }
            tryChild.succeed(Unit)
            child.awaitValue(3.seconds)?.releaseBounded() shouldBe Unit
        } finally {
            finishOuter.succeed(Unit)
            tryChild.succeed(Unit)
            get.releaseBounded()
            cache.closeAndJoin()
        }
    }

    @Test
    fun `finished borrowed context allows a child release while its caller does unrelated work`() {
        val child = Promise<Nothing, CompletableFuture<Unit>>()
        val tryChild = Promise<Nothing, Unit>()
        val outside = Promise<Nothing, Unit>()
        val finishCaller = Promise<Nothing, Unit>()
        lateinit var cache: Cache<String, Nothing, Int>
        cache = Cache({ 1.minutes }, onEvict = { _, _ ->
            child.succeed(surefoldFuture { tryChild.awaitValue(); cache.closeAndJoin() })
        }) { Either.Right(1) }
        cache.get("a")
        val caller = surefoldFuture {
            cache.invalidate(Invalidation.All)
            outside.succeed(Unit)
            finishCaller.releaseAwait()
        }
        try {
            outside.awaitValue(3.seconds) shouldBe Unit
            tryChild.succeed(Unit)
            child.awaitValue(3.seconds)?.releaseBounded() shouldBe Unit
            caller.isDone shouldBe false
        } finally {
            tryChild.succeed(Unit)
            finishCaller.succeed(Unit)
            caller.releaseBounded()
            cache.closeAndJoin()
        }
    }

    @Test
    fun `borrowed cancellation precedes lookup joins and cannot interrupt later caller work`() {
        val sourceEntered = Promise<Nothing, Unit>()
        val sourceRescue = Promise<Nothing, Unit>()
        val callbackEnded = Promise<Nothing, Unit>()
        val outside = Promise<Nothing, Unit>()
        val finishCaller = Promise<Nothing, Unit>()
        val lateInterrupt = Shared(false)
        lateinit var cache: Cache<String, Nothing, Int>
        cache = Cache({ 1.minutes }, onEvict = { key, _ ->
            if (key == "a") {
                try {
                    cache.get("b")
                } finally {
                    callbackEnded.succeed(Unit)
                }
            }
        }) { key ->
            if (key == "b") {
                sourceEntered.succeed(Unit)
                try {
                    sourceRescue.awaitValue()
                } finally {
                    callbackEnded.releaseAwait()
                }
            }
            Either.Right(1)
        }
        cache.get("a")
        val caller = surefoldFuture {
            shouldThrow<InterruptedException> { cache.invalidate(Invalidation.All) }
            Thread.interrupted()
            outside.succeed(Unit)
            try {
                finishCaller.awaitValue()
            } catch (stop: InterruptedException) {
                lateInterrupt.update { true }
                throw stop
            }
        }
        sourceEntered.awaitValue(3.seconds) shouldBe Unit
        val close = surefoldFuture { cache.closeAndJoin() }
        try {
            close.releaseBounded()
            outside.awaitValue(3.seconds) shouldBe Unit
            caller.isDone shouldBe false
            lateInterrupt.peek() shouldBe false
        } finally {
            sourceRescue.succeed(Unit)
            callbackEnded.succeed(Unit)
            finishCaller.succeed(Unit)
            close.releaseBounded()
            caller.releaseBounded()
        }
    }

    @Test
    fun `owner interruption consumed by a failed layer cannot start a fallback lookup`() {
        val entered = Promise<Nothing, Thread>()
        val cancelled = Promise<Nothing, Unit>()
        val readRescue = Promise<Nothing, Unit>()
        val fallbackRescue = Promise<Nothing, Unit>()
        val calls = Shared(0)
        val layer = object : AwaitedLayer() {
            override fun read(key: String): Stored<Int>? {
                entered.succeed(Thread.currentThread())
                try {
                    readRescue.awaitValue()
                } catch (stop: InterruptedException) {
                    cancelled.succeed(Unit)
                    throw stop
                }
                return null
            }
        }
        val cache = Cache<String, Nothing, Int>({ 1.minutes }, layers = listOf(layer)) {
            calls.update { count -> count + 1 }
            fallbackRescue.awaitValue()
            Either.Right(1)
        }
        val get = surefoldFuture { shouldThrow<InterruptedException> { cache.get("a") } }
        entered.awaitValue(3.seconds)?.isAlive shouldBe true
        val close = surefoldFuture { cache.closeAndJoin() }
        try {
            cancelled.awaitValue(3.seconds) shouldBe Unit
            close.releaseBounded()
            calls.peek() shouldBe 0
            cache.stats().loads shouldBe 0
            entered.awaitValue().isAlive shouldBe false
        } finally {
            readRescue.succeed(Unit)
            fallbackRescue.succeed(Unit)
            close.releaseBounded()
            get.releaseBounded()
        }
    }

    @Test
    fun `an interrupted layer while open remains a miss with ordinary fallback behavior`() {
        val layer = object : AwaitedLayer() {
            override fun read(key: String): Stored<Int>? = throw InterruptedException("layer failed")
        }
        val cache = Cache<String, Nothing, Int>({ 1.minutes }, layers = listOf(layer)) { Either.Right(1) }
        try {
            cache.get("a") shouldBe Either.Right(1)
            cache.stats().loads shouldBe 1
            cache.stats().layerFailures shouldBe 1
        } finally {
            cache.closeAndJoin()
        }
    }

    @Test
    fun `retry decision and clock consuming owner interruption cannot start another attempt`() {
        listOf(false, true).forEach { inClock ->
            val entered = Promise<Nothing, Unit>()
            val finish = Promise<Nothing, Unit>()
            val sourceRescue = Promise<Nothing, Unit>()
            val calls = Shared(0)
            val moving = object : Clock {
                override fun now(): Instant = Instant.EPOCH
                override fun sleep(duration: Duration) {
                    if (inClock) { entered.succeed(Unit); finish.releaseAwait() }
                }
            }
            val retry = Schedule<String, Unit> {
                if (!inClock) { entered.succeed(Unit); finish.releaseAwait() }
                Schedule.Decision.Continue(Unit, Duration.ZERO) { Schedule.Decision.Done(Unit) }
            }
            val cache = clock.locally(moving) {
                Cache<String, String, Int>({ 1.minutes }, retry = retry) {
                    if (calls.modify { count -> count + 1 to count } > 0) sourceRescue.awaitValue()
                    Either.Left("busy")
                }
            }
            val get = surefoldFuture { shouldThrow<InterruptedException> { cache.get("a") } }
            entered.awaitValue(3.seconds) shouldBe Unit
            val closing = Promise<Nothing, Unit>()
            val close = metrics.locally(ReleaseMetrics("surefold.cache.backgrounds") { closing.succeed(Unit) }) {
                surefoldFuture { cache.closeAndJoin() }
            }
            try {
                closing.awaitValue(3.seconds) shouldBe Unit
                finish.succeed(Unit)
                close.releaseBounded()
                calls.peek() shouldBe 1
                cache.stats().loads shouldBe 1
            } finally {
                finish.succeed(Unit)
                sourceRescue.succeed(Unit)
                close.releaseBounded()
                get.releaseBounded()
            }
        }
    }

    @Test
    fun `interrupted cleanup invokes every callback once and restores the flag with another defect primary`() {
        val defect = IllegalArgumentException("first cleanup")
        val entered = Promise<Nothing, Thread>()
        val rescue = Promise<Nothing, Unit>()
        val callbacks = Shared(emptyList<String>())
        val cache = Cache<String, Nothing, Int>({ Duration.INFINITE }, onEvict = { key, _ ->
            callbacks.update { it + key }
            if (key == "a") throw defect
            if (key == "b") {
                entered.succeed(Thread.currentThread())
                rescue.awaitValue()
            }
        }) { Either.Right(1) }
        listOf("a", "b", "c").forEach { cache.get(it) }
        val close = surefoldFuture {
            val thrown = shouldThrow<IllegalArgumentException> { cache.closeAndJoin() }
            thrown to Thread.currentThread().isInterrupted
        }
        try {
            entered.awaitValue(3.seconds)?.interrupt()
            close.releaseBounded() shouldBe (defect to true)
            callbacks.peek() shouldBe listOf("a", "b", "c")
            defect.suppressed.single()::class shouldBe InterruptedException::class
            shouldThrow<IllegalArgumentException> { cache.closeAndJoin() } shouldBe defect
            callbacks.peek().size shouldBe 3
            cache.stats().size shouldBe 0
        } finally {
            rescue.succeed(Unit)
            close.releaseBounded()
        }
    }

    @Test
    fun `release metrics and eviction cannot join their own elected leader`() {
        val rejected = Shared(0)
        lateinit var cache: Cache<String, Nothing, Int>
        fun reject() {
            shouldThrow<IllegalStateException> { cache.closeAndJoin() }
            rejected.update { it + 1 }
        }
        cache = Cache({ 1.minutes }, onEvict = { _, _ -> reject() }) { Either.Right(1) }
        cache.get("a")
        val instrumented = ReleaseMetrics("surefold.cache.backgrounds", action = ::reject)
        metrics.locally(instrumented) { cache.closeAndJoin() }
        rejected.peek() shouldBe 2
        cache.stats().size shouldBe 0
    }

    @Test
    fun `metrics defects do not strand private release completion or omit cleanup`() {
        listOf(false, true).forEach { histogram ->
            val defect = IllegalStateException("metric failed")
            val cleaned = Shared(0)
            val cache = Cache<String, Nothing, Int>({ 1.minutes }, onEvict = { _, _ -> cleaned.update { it + 1 } }) {
                Either.Right(1)
            }
            cache.get("a")
            val instrumented = ReleaseMetrics("surefold.cache.backgrounds", histogram) { throw defect }
            metrics.locally(instrumented) {
                shouldThrow<IllegalStateException> { cache.closeAndJoin() } shouldBe defect
            }
            metrics.locally(ReleaseMetrics("promise") { error("private completion emitted metrics") }) {
                shouldThrow<IllegalStateException> { cache.closeAndJoin() } shouldBe defect
            }
            cleaned.peek() shouldBe 1
            cache.stats().size shouldBe 0
        }
    }
}

private class ReleaseMetrics(
    private val transaction: String,
    private val histogram: Boolean = false,
    private val action: () -> Unit,
) : Metrics by NoMetrics {
    private fun run(tags: Map<String, String>) {
        if (tags["transact"] == transaction) metrics.locally(NoMetrics, action)
    }
    override fun counter(name: String, tags: Map<String, String>): Counter = Counter {
        if (!histogram && name == "surefold.transact.runs") run(tags)
    }
    override fun histogram(name: String, tags: Map<String, String>): Histogram = Histogram {
        if (histogram && name == "surefold.transact.attempts") run(tags)
    }
}

private fun terminalSelfClose(histogram: Boolean, fails: Boolean) {
    val worker = Promise<Nothing, Thread>()
    val entered = Promise<Nothing, Unit>()
    val finish = Promise<Nothing, Unit>()
    val defect = IllegalArgumentException("lookup failed")
    val cache = Cache<String, Nothing, Int>({ 1.minutes }) { key ->
        if (key == "a") {
            worker.succeed(Thread.currentThread())
            if (fails) throw defect
        }
        Either.Right(1)
    }
    val instrumented = ReleaseMetrics("surefold.cache", histogram) {
        if (Thread.currentThread() === worker.awaitValue()) {
            shouldThrow<IllegalStateException> { cache.closeAndJoin() }
            entered.succeed(Unit)
            finish.releaseAwait()
        }
    }
    val get = metrics.locally(instrumented) {
        surefoldFuture { if (fails) shouldThrow<IllegalArgumentException> { cache.get("a") } else cache.get("a") }
    }
    try {
        entered.awaitValue(3.seconds) shouldBe Unit
        cache.get("b") shouldBe Either.Right(1)
        val close = surefoldFuture { cache.closeAndJoin() }
        try {
            close.isDone shouldBe false
        } finally {
            finish.succeed(Unit)
            close.releaseBounded()
        }
        worker.awaitValue().isAlive shouldBe false
        get.releaseBounded()
    } finally {
        finish.succeed(Unit)
        cache.closeAndJoin()
    }
}

private open class AwaitedLayer : Layer<String, Int> {
    override fun read(key: String): Stored<Int>? = null
    override fun write(key: String, stored: Stored<Int>) = Unit
    override fun remove(key: String) = Unit
    override fun clear() = Unit
}

private fun <A> CompletableFuture<A>.releaseBounded(): A = get(5, TimeUnit.SECONDS)

private fun Promise<Nothing, Unit>.releaseAwait() {
    while (peek() == null) {
        try {
            awaitValue()
        } catch (_: InterruptedException) {
            // The test releases this resistant callback explicitly; owner close must still await it.
        }
    }
}
