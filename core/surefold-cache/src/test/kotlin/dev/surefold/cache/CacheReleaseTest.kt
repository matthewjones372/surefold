package dev.surefold.cache

import dev.surefold.Clock
import dev.surefold.Promise
import dev.surefold.Schedule
import dev.surefold.Shared
import dev.surefold.TestClock
import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldThrow
import dev.surefold.awaitValue
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.resourceScope
import dev.surefold.surefoldFuture
import dev.surefold.surefoldLocal
import dev.surefold.test.eventually
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(20)
class CacheReleaseTest {
    @Test
    fun `release without work closes get refresh and invalidation but leaves stats readable`() {
        val cache = Cache<String, Nothing, Int>({ 1.minutes }) { Either.Right(1) }
        cache.closeAndJoin()
        shouldThrow<IllegalStateException> { cache.get("a") }
        shouldThrow<IllegalStateException> { cache.refresh("a") }
        shouldThrow<IllegalStateException> { cache.invalidate(Invalidation.Key("a")) }
        cache.stats().size shouldBe 0
        cache.closeAndJoin()
        cache.close()
    }

    @Test
    fun `release joins resistant lookup and finally before clearing without publishing its late answer`() {
        val entered = Promise<Nothing, Thread>()
        val cleaning = Promise<Nothing, Unit>()
        val finish = Promise<Nothing, Unit>()
        val layer = ReleaseLayer()
        val gone = Shared(emptyList<Int>())
        val cache =
            Cache<String, Nothing, Int>(
                { 1.minutes },
                layers = listOf(layer),
                onEvict = { _, value ->
                    gone.update { it + value }
                },
            ) {
                if (it == "held") Either.Right(1)
                else {
                    entered.succeed(Thread.currentThread())
                    try {
                        Promise<Nothing, Unit>().awaitValue()
                    } catch (_: InterruptedException) {
                        cleaning.succeed(Unit)
                        finish.awaitResistingInterrupts()
                    }
                    Either.Right(2)
                }
            }
        cache.get("held") shouldBe Either.Right(1)
        val caller = surefoldFuture { cache.get("late") }
        val worker = entered.awaitValue()
        val closer = Promise<Nothing, Thread>()
        val close = surefoldFuture {
            closer.succeed(Thread.currentThread())
            cache.closeAndJoin()
        }
        try {
            cleaning.awaitValue()
            closer.awaitValue().awaitParked()
            close.isDone shouldBe false
            shouldThrow<IllegalStateException> { cache.get("held") }
            shouldThrow<IllegalStateException> { cache.refresh("new") }
            shouldThrow<IllegalStateException> { cache.invalidate(Invalidation.All) }
            gone.peek() shouldBe emptyList()
        } finally {
            finish.succeed(Unit)
            close.bounded()
        }
        caller.bounded() shouldBe Either.Right(2)
        worker.isAlive shouldBe false
        cache.stats().size shouldBe 0
        gone.peek() shouldBe listOf(1)
        layer.written.peek() shouldBe listOf("held")
        layer.clears.peek() shouldBe 0
        cache.closeAndJoin()
        gone.peek() shouldBe listOf(1)
    }

    @Test
    fun `release joins a resistant layer read without readmitting or rewriting its late hit`() {
        val entered = Promise<Nothing, Thread>()
        val cleaning = Promise<Nothing, Unit>()
        val finish = Promise<Nothing, Unit>()
        val nearer = ReleaseLayer()
        val far =
            object : Layer<String, Int> {
                override fun read(key: String): Stored<Int> {
                    entered.succeed(Thread.currentThread())
                    try {
                        Promise<Nothing, Unit>().awaitValue()
                    } catch (_: InterruptedException) {
                        cleaning.succeed(Unit)
                        finish.awaitResistingInterrupts()
                    }
                    return Stored(7, Instant.EPOCH, Instant.EPOCH.plusSeconds(60))
                }

                override fun write(key: String, stored: Stored<Int>) = Unit

                override fun remove(key: String) = Unit

                override fun clear() = Unit
            }
        val moving = TestClock()
        val cache =
            clock.locally(moving) {
                Cache<String, Nothing, Int>({ 1.minutes }, layers = listOf(nearer, far)) { error("layer hit") }
            }
        val caller = surefoldFuture { cache.get("a") }
        val worker = entered.awaitValue()
        val closer = Promise<Nothing, Thread>()
        val close = surefoldFuture {
            closer.succeed(Thread.currentThread())
            cache.closeAndJoin()
        }
        try {
            cleaning.awaitValue()
            closer.awaitValue().awaitParked()
            close.isDone shouldBe false
        } finally {
            finish.succeed(Unit)
            close.bounded()
        }
        caller.bounded() shouldBe Either.Right(7)
        worker.isAlive shouldBe false
        cache.stats().size shouldBe 0
        nearer.written.peek() shouldBe emptyList()
    }

    @Test
    fun `release does not repeat a worker defect already delivered to its waiter`() {
        val entered = Promise<Nothing, Unit>()
        val failure = IllegalArgumentException("lookup")
        val cache =
            Cache<String, Nothing, Int>({ 1.minutes }) {
                entered.succeed(Unit)
                try {
                    Promise<Nothing, Unit>().awaitValue()
                    Either.Right(1)
                } catch (_: InterruptedException) {
                    throw failure
                }
            }
        val caller = surefoldFuture { shouldThrow<IllegalArgumentException> { cache.get("a") } }
        entered.awaitValue()
        cache.closeAndJoin()
        (caller.bounded() === failure) shouldBe true
        cache.closeAndJoin()
    }

    @Test
    fun `ordinary close returns before finally and awaited close can still join it`() {
        val entered = Promise<Nothing, Unit>()
        val cleaning = Promise<Nothing, Unit>()
        val finish = Promise<Nothing, Unit>()
        val cache =
            Cache<String, Nothing, Int>({ 1.minutes }) {
                entered.succeed(Unit)
                try {
                    Promise<Nothing, Unit>().awaitValue()
                    Either.Right(1)
                } finally {
                    cleaning.succeed(Unit)
                    finish.awaitResistingInterrupts()
                }
            }
        val caller = surefoldFuture { shouldThrow<InterruptedException> { cache.get("k") } }
        entered.awaitValue()
        cache.close()
        cleaning.awaitValue()
        val closer = Promise<Nothing, Thread>()
        val close = surefoldFuture {
            closer.succeed(Thread.currentThread())
            cache.closeAndJoin()
        }
        try {
            closer.awaitValue().awaitParked()
            close.isDone shouldBe false
        } finally {
            finish.succeed(Unit)
            close.bounded()
            caller.bounded()
        }
    }

    @Test
    fun `concurrent and interrupted releases share cleanup and restore interruption`() {
        val cleaning = Promise<Nothing, Thread>()
        val finish = Promise<Nothing, Unit>()
        val calls = Shared(0)
        val cache =
            Cache<String, Nothing, Int>(
                { 1.minutes },
                onEvict = { _, _ ->
                    calls.update { it + 1 }
                    cleaning.succeed(Thread.currentThread())
                    finish.awaitResistingInterrupts()
                },
            ) {
                Either.Right(1)
            }
        cache.get("k")
        val first = surefoldFuture {
            Thread.currentThread().interrupt()
            cache.closeAndJoin()
            Thread.currentThread().isInterrupted
        }
        val thread = cleaning.awaitValue()
        val joining = Promise<Nothing, Thread>()
        val second = surefoldFuture {
            joining.succeed(Thread.currentThread())
            cache.closeAndJoin()
            Thread.currentThread().isInterrupted
        }
        try {
            thread.interrupt()
            joining.awaitValue().interrupt()
            joining.awaitValue().awaitParked()
            first.isDone shouldBe false
            second.isDone shouldBe false
        } finally {
            finish.succeed(Unit)
        }
        first.bounded() shouldBe true
        second.bounded() shouldBe true
        calls.peek() shouldBe 1
    }

    @Test
    fun `release clears every entry even when callbacks fail and shares the cleanup defects`() {
        val sharedValue = Any()
        val keys = Shared(emptyList<String>())
        val defects = mapOf("a" to IllegalArgumentException("a"), "b" to IllegalArgumentException("b"))
        val cache =
            Cache<String, Nothing, Any>(
                { Duration.INFINITE },
                onEvict = { key, value ->
                    (value === sharedValue) shouldBe true
                    keys.update { it + key }
                    throw defects.getValue(key)
                },
            ) {
                Either.Right(sharedValue)
            }
        cache.get("a")
        cache.get("b")
        val failure = shouldThrow<IllegalArgumentException> { cache.closeAndJoin() }
        keys.peek().toSet() shouldBe setOf("a", "b")
        keys.peek().size shouldBe 2
        failure.suppressed.toSet() shouldBe (defects.values - failure).toSet()
        (shouldThrow<IllegalArgumentException> { cache.closeAndJoin() } === failure) shouldBe true
        cache.stats().size shouldBe 0
    }

    @Test
    fun `one reused callback defect still allows cleanup for every removed entry`() {
        val keys = Shared(emptyList<String>())
        val defect = IllegalArgumentException("cleanup")
        val cache =
            Cache<String, Nothing, Int>(
                { Duration.INFINITE },
                onEvict = { key, _ ->
                    keys.update { it + key }
                    throw defect
                },
            ) {
                Either.Right(1)
            }
        listOf("a", "b", "c").forEach { cache.get(it) }
        (shouldThrow<IllegalArgumentException> { cache.closeAndJoin() } === defect) shouldBe true
        keys.peek().toSet() shouldBe setOf("a", "b", "c")
        defect.suppressed.toList() shouldBe emptyList()
        cache.stats().size shouldBe 0
    }

    @Test
    fun `self close from lookup eviction and layer callbacks is refused before admission changes`() {
        lateinit var cache: Cache<String, Nothing, Int>
        val rejected = Shared(0)
        fun reject() {
            shouldThrow<IllegalStateException> { cache.closeAndJoin() }
            rejected.update { it + 1 }
        }
        val layer = ReleaseLayer(onWrite = { reject() }, onRemove = { reject() })
        cache =
            Cache({ 1.minutes }, layers = listOf(layer), onEvict = { _, _ -> reject() }) {
                reject()
                Either.Right(1)
            }
        cache.get("a") shouldBe Either.Right(1)
        cache.invalidate(Invalidation.Key("a"))
        cache.get("b") shouldBe Either.Right(1)
        cache.closeAndJoin()
        rejected.peek() shouldBe 7
        cache.stats().size shouldBe 0
    }

    @Test
    fun `release waits for accepted invalidation and its eviction callback`() {
        val entered = Promise<Nothing, Unit>()
        val finish = Promise<Nothing, Unit>()
        val ended = Shared(false)
        val cache =
            Cache<String, Nothing, Int>(
                { 1.minutes },
                onEvict = { _, _ ->
                    entered.succeed(Unit)
                    finish.awaitResistingInterrupts()
                    ended.update { true }
                },
            ) {
                Either.Right(1)
            }
        cache.get("a")
        val invalidation = surefoldFuture { cache.invalidate(Invalidation.All) }
        entered.awaitValue()
        val closer = Promise<Nothing, Thread>()
        val close = surefoldFuture {
            closer.succeed(Thread.currentThread())
            cache.closeAndJoin()
        }
        try {
            closer.awaitValue().awaitParked()
            close.isDone shouldBe false
        } finally {
            finish.succeed(Unit)
            close.bounded()
            invalidation.bounded()
        }
        ended.peek() shouldBe true
    }

    @Test
    fun `release awaits accepted invalidation without joining its callers unrelated lifetime`() {
        val entered = Promise<Nothing, Unit>()
        val cancelled = Promise<Nothing, Unit>()
        val outside = Promise<Nothing, Unit>()
        val finish = Promise<Nothing, Unit>()
        val layer =
            ReleaseLayer(
                onRemove = {
                    entered.succeed(Unit)
                    try {
                        Promise<Nothing, Unit>().awaitValue()
                    } finally {
                        cancelled.succeed(Unit)
                    }
                },
            )
        val cache = Cache<String, Nothing, Int>({ 1.minutes }, layers = listOf(layer)) { Either.Right(1) }
        val callerThread = Promise<Nothing, Thread>()
        val caller = surefoldFuture {
            callerThread.succeed(Thread.currentThread())
            cache.invalidate(Invalidation.Key("a"))
            outside.succeed(Unit)
            finish.awaitResistingInterrupts()
        }
        entered.awaitValue()
        val close = surefoldFuture { cache.closeAndJoin() }
        try {
            close.bounded()
            cancelled.peek() shouldBe Either.Right(Unit)
            outside.awaitValue()
            caller.isDone shouldBe false
        } finally {
            finish.succeed(Unit)
            callerThread.awaitValue().interrupt()
            caller.bounded()
        }
    }

    @Test
    fun `release can cancel a cooperative layer callback while it is blocked`() {
        val entered = Promise<Nothing, Unit>()
        val cancelled = Promise<Nothing, Unit>()
        val layer =
            ReleaseLayer(
                onWrite = {
                    entered.succeed(Unit)
                    try {
                        Promise<Nothing, Unit>().awaitValue()
                    } finally {
                        cancelled.succeed(Unit)
                    }
                },
            )
        val cache = Cache<String, Nothing, Int>({ 1.minutes }, layers = listOf(layer)) { Either.Right(1) }
        val caller = surefoldFuture { cache.get("a") }
        entered.awaitValue()
        val close = surefoldFuture { cache.closeAndJoin() }
        try {
            close.bounded()
            cancelled.peek() shouldBe Either.Right(Unit)
            caller.bounded() shouldBe Either.Right(1)
            cache.stats().size shouldBe 0
        } finally {
            cache.close()
        }
    }

    @Test
    fun `release joins a layer write already publishing before clearing memory`() {
        val entered = Promise<Nothing, Thread>()
        val finish = Promise<Nothing, Unit>()
        val gone = Shared(false)
        val layer =
            ReleaseLayer(
                onWrite = {
                    entered.succeed(Thread.currentThread())
                    finish.awaitResistingInterrupts()
                },
            )
        val cache =
            Cache<String, Nothing, Int>(
                { 1.minutes },
                layers = listOf(layer),
                onEvict = { _, _ ->
                    gone.update { true }
                },
            ) {
                Either.Right(1)
            }
        val caller = surefoldFuture { cache.get("a") }
        entered.awaitValue()
        val closer = Promise<Nothing, Thread>()
        val close = surefoldFuture {
            closer.succeed(Thread.currentThread())
            cache.closeAndJoin()
        }
        try {
            closer.awaitValue().awaitParked()
            close.isDone shouldBe false
            gone.peek() shouldBe false
        } finally {
            finish.succeed(Unit)
            close.bounded()
            caller.bounded()
        }
        gone.peek() shouldBe true
        layer.written.peek() shouldBe listOf("a")
    }

    @Test
    fun `release owns displaced finite and zero delay sleepers until their finally blocks end`() {
        listOf(Duration.ZERO, 1.seconds).forEach { delay ->
            val moving = ResistantClock()
            val cache =
                clock.locally(moving) {
                    Cache<String, Nothing, Int>({ 1.minutes }, refreshing = Schedule.spaced(delay)) { Either.Right(1) }
                }
            cache.get("a")
            awaitCondition { moving.entered.peek().size == 1 }
            cache.refresh("a")
            awaitCondition { moving.entered.peek().size == 2 }
            val closer = Promise<Nothing, Thread>()
            val close = surefoldFuture {
                closer.succeed(Thread.currentThread())
                cache.closeAndJoin()
            }
            try {
                awaitCondition { moving.cleaning.peek() == 2 }
                closer.awaitValue().awaitParked()
                close.isDone shouldBe false
            } finally {
                moving.entered.peek().forEach { it.interrupt() }
                moving.finish.succeed(Unit)
                close.bounded()
            }
            moving.entered.peek().all { !it.isAlive } shouldBe true
            cache.ahead() shouldBe 0
        }
    }

    @Test
    fun `a background sleeper cannot close its own cache`() {
        lateinit var cache: Cache<String, Nothing, Int>
        val rejected = Promise<Nothing, Boolean>()
        val moving =
            object : Clock {
                override fun now(): Instant = Instant.EPOCH

                override fun sleep(duration: Duration) {
                    val refused =
                        try {
                            cache.closeAndJoin()
                            false
                        } catch (_: IllegalStateException) {
                            true
                        }
                    rejected.succeed(refused)
                    if (refused) Promise<Nothing, Unit>().awaitValue()
                }
            }
        cache =
            clock.locally(moving) {
                Cache({ 1.minutes }, refreshing = Schedule.spaced(1.seconds)) { Either.Right(1) }
            }
        cache.get("a")
        try {
            rejected.awaitValue() shouldBe true
            cache.get("a") shouldBe Either.Right(1)
        } finally {
            cache.closeAndJoin()
        }
    }

    @Test
    fun `release joins explicit refresh lookup before releasing its dependency`() {
        val entered = Promise<Nothing, Unit>()
        val cleaning = Promise<Nothing, Unit>()
        val finish = Promise<Nothing, Unit>()
        val alive = Shared(true)
        val order = Shared(emptyList<String>())
        val opened = Promise<Nothing, Cache<String, Nothing, Int>>()
        val use = surefoldFuture {
            resourceScope {
                onRelease {
                    alive.update { false }
                    order.update { steps -> steps + "dependency" }
                }
                val cache =
                    install(
                        {
                            Cache<String, Nothing, Int>({ 1.minutes }) {
                                entered.succeed(Unit)
                                try {
                                    Promise<Nothing, Unit>().awaitValue()
                                    Either.Right(1)
                                } finally {
                                    cleaning.succeed(Unit)
                                    finish.awaitResistingInterrupts()
                                    alive.peek() shouldBe true
                                    order.update { steps -> steps + "lookup" }
                                }
                            }
                        },
                    ) { cache, _ ->
                        cache.closeAndJoin()
                        order.update { steps -> steps + "cache" }
                    }
                opened.succeed(cache)
                cache.refresh("a")
                entered.awaitValue()
            }
        }
        opened.awaitValue()
        try {
            cleaning.awaitValue()
            use.isDone shouldBe false
            alive.peek() shouldBe true
        } finally {
            finish.succeed(Unit)
            use.bounded()
        }
        order.peek() shouldBe listOf("lookup", "cache", "dependency")
    }

    @Test
    fun `refresh keeps first caller bindings and the construction clock`() {
        val bound = surefoldLocal { "unbound" }
        val moving = TestClock()
        val seen = Promise<Nothing, String>()
        val cache =
            clock.locally(moving) {
                Cache<String, Nothing, String>({ 1.seconds }) {
                    seen.succeed(bound.get())
                    Either.Right(bound.get())
                }
            }
        bound.locally("owner") { cache.refresh("a") }
        seen.awaitValue() shouldBe "owner"
        awaitCondition { cache.stats().size == 1 }
        cache.get("a") shouldBe Either.Right("owner")
        moving.adjust(1.seconds)
        bound.locally("next") { cache.get("a") } shouldBe Either.Right("next")
        cache.closeAndJoin()
    }
}

private class ReleaseLayer(
    private val onWrite: () -> Unit = {},
    private val onRemove: () -> Unit = {},
) : Layer<String, Int> {
    val written = Shared(emptyList<String>())
    val clears = Shared(0)

    override fun read(key: String): Stored<Int>? = null

    override fun write(key: String, stored: Stored<Int>) {
        onWrite()
        written.update { it + key }
    }

    override fun remove(key: String) = onRemove()

    override fun clear() {
        clears.update { it + 1 }
    }
}

private class ResistantClock : Clock {
    val entered = Shared(emptyList<Thread>())
    val cleaning = Shared(0)
    val finish = Promise<Nothing, Unit>()

    override fun now(): Instant = Instant.EPOCH

    override fun sleep(duration: Duration) {
        entered.update { it + Thread.currentThread() }
        try {
            Promise<Nothing, Unit>().awaitValue()
        } finally {
            cleaning.update { it + 1 }
            finish.awaitResistingInterrupts()
        }
    }
}

private fun awaitCondition(done: () -> Boolean) = eventually(5.seconds) { done() shouldBe true }

private fun Thread.awaitParked() = awaitCondition { state == Thread.State.WAITING || !isAlive }

private fun <A> CompletableFuture<A>.bounded(): A = get(5, TimeUnit.SECONDS)

private fun Promise<Nothing, Unit>.awaitResistingInterrupts() {
    while (true) {
        try {
            awaitValue()
            return
        } catch (_: InterruptedException) {
            // The owner must join this cleanup even when it resists cancellation.
        }
    }
}
