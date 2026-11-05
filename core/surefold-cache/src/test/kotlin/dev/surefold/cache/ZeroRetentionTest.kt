package dev.surefold.cache

import dev.surefold.Promise
import dev.surefold.Schedule
import dev.surefold.Shared
import dev.surefold.TestClock
import dev.surefold.assert.shouldBe
import dev.surefold.awaitValue
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.parMap
import dev.surefold.parZip
import dev.surefold.surefoldFuture
import dev.surefold.test.eventually
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(20)
class ZeroRetentionTest {
    @Test
    fun `overlapping zero Right and Left answers share one lookup and later calls reload`() {
        listOf(Either.Right(7), Either.Left("down")).forEach { answer ->
            val calls = Shared(0)
            val release = Promise<Nothing, Unit>()
            val gone = Shared(emptyList<Int>())
            val layer = RecordingLayer()
            Cache<String, String, Int>(
                    { Duration.ZERO },
                    layers = listOf(layer),
                    onEvict = { _, value -> gone.update { it + value } },
                ) {
                    calls.update { count -> count + 1 }
                    release.awaitValue()
                    answer
                }
                .use { cache ->
                    val answers =
                        parZip(
                            { parMap(1..4) { cache.get("key") } },
                            {
                                until { cache.waiting("key") == 4 }
                                release.succeed(Unit)
                            },
                        ) { all, _ ->
                            all
                        }
                    answers shouldBe List(4) { answer }
                    calls.peek() shouldBe 1
                    cache.stats() shouldBe CacheStats(0, 4, 1, if (answer is Either.Left) 1 else 0, 0, 0, 0, 0, 0)
                    cache.get("key") shouldBe answer
                    calls.peek() shouldBe 2
                    cache.invalidate(Invalidation.All)
                    cache.stats().evictions shouldBe 0
                    gone.peek() shouldBe emptyList()
                    layer.writes.peek() shouldBe emptyList()
                }
        }
    }

    @Test
    fun `cancelling a zero-result waiter leaves the shared lookup for the other waiter`() {
        val release = Promise<Nothing, Unit>()
        val calls = Shared(0)
        val cancelled = Promise<Nothing, Unit>()
        Cache<String, Nothing, Int>({ Duration.ZERO }) {
                calls.update { count -> count + 1 }
                release.awaitValue()
                Either.Right(7)
            }
            .use { cache ->
                val first = surefoldFuture {
                    try {
                        cache.get("key")
                    } catch (_: InterruptedException) {
                        cancelled.succeed(Unit)
                    }
                }
                val other = surefoldFuture { cache.get("key") }
                until { cache.waiting("key") == 2 }
                first.cancel(true)
                cancelled.awaitValue()
                cache.waiting("key") shouldBe 1
                release.succeed(Unit)
                other.get() shouldBe Either.Right(7)
                calls.peek() shouldBe 1
                cache.stats().size shouldBe 0
            }
    }

    @Test
    fun `zero-result churn cannot use the retained quota or evict a retained value`() {
        val gone = Shared(emptyList<Int>())
        Cache<Int, Nothing, Int>(
                { if (it == Either.Right(0)) 1.minutes else Duration.ZERO },
                capacity = 1,
                onEvict = { _, value -> gone.update { it + value } },
            ) {
                Either.Right(it)
            }
            .use { cache ->
                cache.get(0)
                (1..100).forEach { key -> repeat(3) { cache.get(key) } }
                cache.stats().size shouldBe 1
                cache.stats().evictions shouldBe 0
                gone.peek() shouldBe emptyList()
                cache.get(0) shouldBe Either.Right(0)
                cache.stats().hits shouldBe 1
                cache.invalidate(Invalidation.All)
                gone.peek() shouldBe listOf(0)
                cache.stats().evictions shouldBe 1
            }
    }

    @Test
    fun `a zero-result layer hit is delivered without memory or nearer-layer admission`() {
        val moving = TestClock()
        val stored = Stored(7, Instant.EPOCH, Instant.EPOCH.plusSeconds(30))
        val near = RecordingLayer()
        val far = RecordingLayer(stored)
        val calls = Shared(0)
        clock
            .locally(moving) {
                Cache<String, Nothing, Int>({ Duration.ZERO }, layers = listOf(near, far)) {
                    calls.update { count -> count + 1 }
                    Either.Right(9)
                }
            }
            .use { cache ->
                repeat(2) { cache.get("key") shouldBe Either.Right(7) }
                cache.stats() shouldBe CacheStats(0, 2, 0, 0, 0, 0, 2, 0, 0)
                near.writes.peek() shouldBe emptyList()
                far.writes.peek() shouldBe emptyList()
                far.record.peek() shouldBe stored
                moving.adjust(30.seconds)
                cache.get("key") shouldBe Either.Right(9)
                calls.peek() shouldBe 1
                cache.stats().size shouldBe 0
            }
    }

    @Test
    fun `a zero answer never starts a refresh schedule`() {
        val moving = TestClock()
        clock
            .locally(moving) {
                Cache<String, Nothing, Int>({ Duration.ZERO }, refreshing = Schedule.spaced(1.seconds)) {
                    Either.Right(7)
                }
            }
            .use { cache ->
                cache.get("key") shouldBe Either.Right(7)
                cache.ahead() shouldBe 0
                moving.sleepers() shouldBe 0
            }
    }

    @Test
    fun `a zero refresh stops its sleeper without cancelling the active lookup needed by waiters`() {
        val moving = TestClock()
        val calls = Shared(0)
        val release = Promise<Nothing, Unit>()
        val gone = Shared(emptyList<Int>())
        clock
            .locally(moving) {
                Cache<String, Nothing, Int>(
                    { if (it == Either.Right(1)) 1.seconds else Duration.ZERO },
                    refreshing = Schedule.spaced(5.seconds),
                    onEvict = { _, value -> gone.update { it + value } },
                ) {
                    val call = calls.modify { count -> count + 1 to count + 1 }
                    if (call == 2) release.awaitValue()
                    Either.Right(call)
                }
            }
            .use { cache ->
                cache.get("key")
                until { moving.sleepers() == 1 }
                cache.get("key")
                moving.adjust(5.seconds)
                calls.awaitUntil { it == 2 }
                val waiter = surefoldFuture { cache.get("key") }
                until { cache.waiting("key") == 2 }
                release.succeed(Unit)
                waiter.get() shouldBe Either.Right(2)
                until { cache.ahead() == 0 && moving.sleepers() == 0 }
                cache.stats().size shouldBe 0
                gone.peek() shouldBe listOf(1)
                cache.stats().evictions shouldBe 1
                cache.get("key") shouldBe Either.Right(3)
            }
    }

    @Test
    fun `a zero failure answers stale once and removes the expired retained value`() {
        val moving = TestClock()
        val source = Shared<Either<String, Int>>(Either.Right(1))
        val gone = Shared(emptyList<Int>())
        clock
            .locally(moving) {
                Cache<String, String, Int>(
                    { if (it is Either.Right) 1.seconds else Duration.ZERO },
                    stale = 1.minutes,
                    onEvict = { _, value -> gone.update { it + value } },
                ) {
                    source.peek()
                }
            }
            .use { cache ->
                cache.get("key")
                source.update { Either.Left("down") }
                moving.adjust(1.seconds)
                cache.get("key") shouldBe Either.Right(1)
                cache.stats() shouldBe CacheStats(0, 2, 2, 1, 1, 1, 0, 0, 0)
                gone.peek() shouldBe listOf(1)
                cache.get("key") shouldBe Either.Left("down")
                cache.stats().failures shouldBe 2
                cache.stats().evictions shouldBe 1
            }
    }

    @Test
    fun `a zero refresh failure keeps the young retained answer and stops refreshing it`() {
        val moving = TestClock()
        val calls = Shared(0)
        val release = Promise<Nothing, Unit>()
        val gone = Shared(emptyList<Int>())
        clock
            .locally(moving) {
                Cache<String, String, Int>(
                    { if (it is Either.Right) 1.minutes else Duration.ZERO },
                    refreshing = Schedule.spaced(5.seconds),
                    onEvict = { _, value -> gone.update { it + value } },
                ) {
                    if (calls.modify { count -> count + 1 to count + 1 } == 1) Either.Right(1)
                    else {
                        release.awaitValue()
                        Either.Left("down")
                    }
                }
            }
            .use { cache ->
                cache.get("key")
                until { moving.sleepers() == 1 }
                cache.refresh("key")
                calls.awaitUntil { it == 2 }
                release.succeed(Unit)
                until { cache.stats().failures == 1L && cache.ahead() == 0 && cache.waiting("key") == 0 }
                cache.get("key") shouldBe Either.Right(1)
                cache.stats().size shouldBe 1
                gone.peek() shouldBe emptyList()
                until { moving.sleepers() == 0 }
                moving.adjust(1.minutes)
                cache.get("key") shouldBe Either.Left("down")
                gone.peek() shouldBe listOf(1)
            }
    }

    @Test
    fun `a failed young refresh spanning invalidation cannot restart its schedule`() {
        val moving = TestClock()
        val release = Promise<Nothing, Unit>()
        val calls = Shared(0)
        val ttlEntered = Promise<Nothing, Unit>()
        val ttlRelease = Promise<Nothing, Unit>()
        clock
            .locally(moving) {
                Cache<String, String, Int>(
                    {
                        if (it is Either.Left) {
                            ttlEntered.succeed(Unit)
                            ttlRelease.awaitValue()
                        }
                        1.minutes
                    },
                    refreshing = Schedule.spaced(5.seconds),
                ) {
                    if (calls.modify { count -> count + 1 to count + 1 } == 1) Either.Right(1)
                    else {
                        release.awaitValue()
                        Either.Left("down")
                    }
                }
            }
            .use { cache ->
                cache.get("key")
                until { moving.sleepers() == 1 }
                cache.refresh("key")
                calls.awaitUntil { it == 2 }
                release.succeed(Unit)
                ttlEntered.awaitValue()
                cache.invalidate(Invalidation.All)
                moving.adjust(5.seconds)
                until { cache.ahead() == 0 }
                ttlRelease.succeed(Unit)
                until { cache.stats().failures == 1L && cache.waiting("key") == 0 }
                cache.ahead() shouldBe 0
                cache.stats().size shouldBe 0
            }
    }

    private fun until(done: () -> Boolean) {
        eventually(5.seconds) { done() shouldBe true }
    }
}

private class RecordingLayer(initial: Stored<Int>? = null) : Layer<String, Int> {
    val record = Shared(initial)
    val writes = Shared(emptyList<Stored<Int>>())

    override fun read(key: String): Stored<Int>? = record.peek()

    override fun write(key: String, stored: Stored<Int>) {
        writes.update { it + stored }
        record.update { stored }
    }

    override fun remove(key: String) = record.update { null }

    override fun clear() = record.update { null }
}
