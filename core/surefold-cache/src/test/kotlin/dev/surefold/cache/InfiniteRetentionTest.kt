package dev.surefold.cache

import dev.surefold.Promise
import dev.surefold.Schedule
import dev.surefold.Shared
import dev.surefold.TestClock
import dev.surefold.assert.shouldBe
import dev.surefold.awaitValue
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.surefoldFuture
import dev.surefold.test.eventually
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class InfiniteRetentionTest {
    @Test
    fun `infinite Right and Left have no deadline and fresh results never reach layers`() {
        listOf(Either.Right(7), Either.Left("down")).forEach { answer ->
            val moving = TestClock()
            val layer = InfiniteLayer()
            val gone = Shared(emptyList<Int>())
            clock.locally(moving) {
                Cache<String, String, Int>(
                    { Duration.INFINITE },
                    layers = listOf(layer),
                    onEvict = { _, value -> gone.update { it + value } },
                ) { answer }
            }.use { cache ->
                cache.get("key") shouldBe answer
                moving.setTime(Instant.MAX)
                repeat(2) { cache.get("key") shouldBe answer }
                cache.stats() shouldBe CacheStats(2, 1, 1, if (answer is Either.Left) 1 else 0, 0, 0, 0, 0, 1)
                layer.writes.peek() shouldBe emptyList()
                cache.invalidate(Invalidation.Key("key"))
                cache.stats().size shouldBe 0
                cache.stats().evictions shouldBe 1
                gone.peek() shouldBe if (answer is Either.Right) listOf(7) else emptyList()
                cache.get("key") shouldBe answer
                cache.stats().loads shouldBe 2
                cache.invalidate(Invalidation.All)
                cache.stats().size shouldBe 0
                cache.stats().evictions shouldBe 2
            }
        }
    }

    @Test
    fun `infinite Right and Left still compete for capacity through TinyLFU`() {
        listOf(Either.Right(7), Either.Left("down")).forEach { answer ->
            val gone = Shared(emptyList<String>())
            Cache<String, String, Int>(
                { Duration.INFINITE },
                capacity = 1,
                onEvict = { key, _ -> gone.update { it + key } },
            ) { answer }.use { cache ->
                cache.get("old") shouldBe answer
                repeat(3) { cache.get("new") shouldBe answer }
                cache.stats().size shouldBe 1
                cache.stats().evictions shouldBe 1
                cache.get("new") shouldBe answer
                cache.stats().hits shouldBe 2
                gone.peek() shouldBe if (answer is Either.Right) listOf("old") else emptyList()
                cache.invalidate(Invalidation.All)
                cache.stats().evictions shouldBe 2
                gone.peek() shouldBe if (answer is Either.Right) listOf("old", "new") else emptyList()
            }
        }
    }

    @Test
    fun `finite layer hits preserve their expiry in memory and nearer layers under infinite policy`() {
        val moving = TestClock()
        val stored = Stored(7, Instant.EPOCH, Instant.EPOCH.plusSeconds(10))
        val near = InfiniteLayer()
        val far = InfiniteLayer(stored)
        clock.locally(moving) {
            Cache<String, Nothing, Int>({ Duration.INFINITE }, layers = listOf(near, far)) { Either.Right(9) }
        }.use { cache ->
            cache.get("key") shouldBe Either.Right(7)
            near.writes.peek() shouldBe listOf(stored)
            far.writes.peek() shouldBe emptyList()
            moving.adjust(9.seconds)
            cache.get("key") shouldBe Either.Right(7)
            moving.adjust(1.seconds)
            cache.get("key") shouldBe Either.Right(9)
            cache.stats() shouldBe CacheStats(1, 2, 1, 0, 0, 0, 1, 0, 1)
            near.writes.peek() shouldBe listOf(stored)
            far.record.peek() shouldBe stored
            moving.setTime(Instant.MAX)
            cache.get("key") shouldBe Either.Right(9)
        }
    }

    @Test
    fun `an infinite failure cannot extend the finite stale deadline`() {
        val moving = TestClock()
        val source = Shared<Either<String, Int>>(Either.Right(1))
        val gone = Shared(emptyList<Int>())
        clock.locally(moving) {
            Cache<String, String, Int>(
                { if (it is Either.Right) 1.seconds else Duration.INFINITE },
                stale = 10.seconds,
                onEvict = { _, value -> gone.update { it + value } },
            ) { source.peek() }
        }.use { cache ->
            cache.get("key") shouldBe Either.Right(1)
            source.update { Either.Left("down") }
            moving.adjust(1.seconds)
            cache.get("key") shouldBe Either.Right(1)
            moving.adjust(8.seconds)
            cache.get("key") shouldBe Either.Right(1)
            moving.adjust(1.seconds)
            cache.get("key") shouldBe Either.Left("down")
            moving.setTime(Instant.MAX)
            cache.get("key") shouldBe Either.Left("down")
            cache.stats() shouldBe CacheStats(2, 3, 3, 2, 2, 0, 0, 0, 1)
            gone.peek() shouldBe listOf(1)
        }
    }

    @Test
    fun `explicit refresh replaces infinite values once and a young failure preserves them`() {
        val moving = TestClock()
        val source = Shared<Either<String, Int>>(Either.Right(1))
        val gone = Shared(emptyList<Int>())
        clock.locally(moving) {
            Cache<String, String, Int>(
                { Duration.INFINITE },
                onEvict = { _, value -> gone.update { it + value } },
            ) { source.peek() }
        }.use { cache ->
            cache.get("key") shouldBe Either.Right(1)
            moving.setTime(Instant.MAX)
            source.update { Either.Left("down") }
            cache.refresh("key")
            until { cache.stats().failures == 1L && cache.waiting("key") == 0 }
            cache.get("key") shouldBe Either.Right(1)
            gone.peek() shouldBe emptyList()
            source.update { Either.Right(2) }
            cache.refresh("key")
            until { gone.peek() == listOf(1) && cache.waiting("key") == 0 }
            cache.get("key") shouldBe Either.Right(2)
            cache.invalidate(Invalidation.All)
            gone.peek() shouldBe listOf(1, 2)
            cache.stats() shouldBe CacheStats(2, 1, 3, 1, 0, 1, 0, 0, 0)
        }
    }

    @Test
    fun `an infinite result spanning invalidation answers waiters without publication or scheduling`() {
        val entered = Promise<Nothing, Unit>()
        val release = Promise<Nothing, Unit>()
        val layer = InfiniteLayer()
        val gone = Shared(emptyList<Int>())
        val moving = TestClock()
        clock.locally(moving) {
            Cache<String, Nothing, Int>(
                {
                    entered.succeed(Unit)
                    release.awaitValue()
                    Duration.INFINITE
                },
                layers = listOf(layer),
                refreshing = Schedule.spaced(1.seconds),
                onEvict = { _, value -> gone.update { it + value } },
            ) { Either.Right(7) }
        }.use { cache ->
            val answer = surefoldFuture { cache.get("key") }
            entered.awaitValue()
            cache.invalidate(Invalidation.All)
            release.succeed(Unit)
            answer.get() shouldBe Either.Right(7)
            cache.stats().size shouldBe 0
            cache.stats().evictions shouldBe 0
            cache.ahead() shouldBe 0
            moving.sleepers() shouldBe 0
            layer.writes.peek() shouldBe emptyList()
            gone.peek() shouldBe emptyList()
        }
    }

    @Test
    fun `an infinite refresh delay owns no sleeper and explicit refresh can resume finite scheduling`() {
        val moving = TestClock()
        val delay = Shared(Duration.INFINITE)
        val calls = Shared(0)
        clock.locally(moving) {
            Cache<String, Nothing, Int>(
                { 30.seconds },
                refreshing = Schedule.spaced<Either<Nothing, Int>>(1.seconds).delayed { _, _ -> delay.peek() },
            ) { Either.Right(calls.modify { count -> count + 1 to count + 1 }) }
        }.use { cache ->
            cache.get("key") shouldBe Either.Right(1)
            cache.ahead() shouldBe 0
            moving.sleepers() shouldBe 0
            delay.update { 1.seconds }
            cache.refresh("key")
            until { moving.sleepers() == 1 && cache.waiting("key") == 0 }
            cache.get("key") shouldBe Either.Right(2)
            moving.adjust(1.seconds)
            until { calls.peek() == 3 && moving.sleepers() == 1 && cache.waiting("key") == 0 }
            cache.get("key") shouldBe Either.Right(3)
        }
    }

    @Test
    fun `infinite retention still permits finite refresh ahead`() {
        val moving = TestClock()
        val calls = Shared(0)
        clock.locally(moving) {
            Cache<String, Nothing, Int>({ Duration.INFINITE }, refreshing = Schedule.spaced(1.seconds)) {
                Either.Right(calls.modify { count -> count + 1 to count + 1 })
            }
        }.use { cache ->
            cache.get("key") shouldBe Either.Right(1)
            until { moving.sleepers() == 1 }
            cache.get("key") shouldBe Either.Right(1)
            moving.adjust(1.seconds)
            until { calls.peek() == 2 && moving.sleepers() == 1 && cache.waiting("key") == 0 }
            cache.get("key") shouldBe Either.Right(2)
        }
    }

    private fun until(done: () -> Boolean) {
        eventually(5.seconds) { done() shouldBe true }
    }
}

private class InfiniteLayer(initial: Stored<Int>? = null) : Layer<String, Int> {
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
