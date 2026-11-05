package dev.surefold.cache

import dev.surefold.Promise
import dev.surefold.Schedule
import dev.surefold.Shared
import dev.surefold.TestClock
import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldThrow
import dev.surefold.awaitValue
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.surefoldFuture
import dev.surefold.test.eventually
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(20)
class CallbackOrderingTest {
    @Test
    fun `replacement eviction invalidation suppresses later old writes and scheduling`() {
        val moving = TestClock()
        val calls = Shared(0)
        val layer = CallbackLayer()
        val gone = Promise<Nothing, Unit>()
        val decisions = Shared(0)
        val schedule =
            Schedule.spaced<Either<Nothing, Int>>(10.seconds).doWhile { _, _ ->
                decisions.update { it + 1 }
                true
            }
        lateinit var cache: Cache<String, Nothing, Int>
        cache =
            clock.locally(moving) {
                Cache(
                    { 1.minutes },
                    layers = listOf(layer),
                    refreshing = schedule,
                    onEvict = { _, _ ->
                        cache.invalidate(Invalidation.Key("a"))
                        gone.succeed(Unit)
                    },
                ) {
                    Either.Right(calls.modify { count -> count + 1 to count + 1 })
                }
            }
        cache.use { _ ->
            cache.get("a") shouldBe Either.Right(1)
            cache.refresh("a")
            gone.awaitValue(3.seconds) shouldBe Unit
            eventually(3.seconds) { cache.waiting("a") shouldBe 0 }
            surefoldFuture { cache.get("b") }.get(3, TimeUnit.SECONDS)
            layer.writes.peek().filter { it.first == "a" } shouldBe listOf("a" to 1)
            cache.ahead() shouldBe 2
            decisions.peek() shouldBe 2
        }
    }

    @Test
    fun `invalidation eviction may get and retain a fresh value`() {
        val calls = Shared(0)
        val fresh = Shared<Either<Nothing, Int>?>(null)
        lateinit var cache: Cache<String, Nothing, Int>
        cache =
            Cache(
                { 1.minutes },
                onEvict = { key, _ ->
                    val answer = cache.get(key)
                    fresh.update { answer }
                },
            ) {
                Either.Right(calls.modify { count -> count + 1 to count + 1 })
            }
        cache.use { _ ->
            cache.get("a") shouldBe Either.Right(1)
            cache.invalidate(Invalidation.All)
            fresh.peek() shouldBe Either.Right(2)
            cache.get("a") shouldBe Either.Right(2)
            calls.peek() shouldBe 2
        }
    }

    @Test
    fun `eviction and ttl may get another missing key and ttl invalidation suppresses publication`() {
        val calls = Shared(0)
        val fire = Shared(false)
        lateinit var cache: Cache<String, Nothing, Int>
        cache =
            Cache(
                {
                    if (fire.modify { was -> true to !was }) {
                        cache.get("b") shouldBe Either.Right(2)
                        cache.invalidate(Invalidation.Key("b"))
                    }
                    1.minutes
                },
                onEvict = { key, _ -> if (key == "b") cache.get("c") },
            ) {
                Either.Right(calls.modify { count -> count + 1 to count + 1 })
            }
        cache.use { _ ->
            cache.get("a") shouldBe Either.Right(1)
            cache.stats().size shouldBe 1
            cache.get("c") shouldBe Either.Right(3)
            calls.peek() shouldBe 3
        }
    }

    @Test
    fun `one repeated callback defect still finishes every removed notification`() {
        val calls = Shared(emptyList<String>())
        val marker = IllegalStateException("eviction defect")
        Cache<String, Nothing, Int>(
                { 1.minutes },
                onEvict = { key, _ ->
                    calls.update { it + key }
                    throw marker
                },
            ) {
                Either.Right(1)
            }
            .use { cache ->
                cache.get("a")
                cache.get("b")
                shouldThrow<IllegalStateException> { cache.invalidate(Invalidation.All) } shouldBe marker
                calls.peek().toSet() shouldBe setOf("a", "b")
                cache.stats().size shouldBe 0
                cache.stats().evictions shouldBe 2
            }
    }

    @Test
    fun `unrelated invalidation suppresses an outcome not yet admitted in every retention mode`() {
        listOf(Duration.ZERO, Duration.INFINITE, 1.minutes).forEach { retention ->
            val entered = Promise<Nothing, Unit>()
            val release = Promise<Nothing, Unit>()
            val layer = CallbackLayer()
            Cache<String, Nothing, Int>(
                    { retention },
                    layers = listOf(layer),
                    refreshing = Schedule.spaced(10.seconds),
                ) {
                    entered.succeed(Unit)
                    release.awaitValue()
                    Either.Right(1)
                }
                .use { cache ->
                    val answer = surefoldFuture { cache.get("a") }
                    try {
                        entered.awaitValue(3.seconds) shouldBe Unit
                        cache.invalidate(Invalidation.Key("b"))
                    } finally {
                        release.succeed(Unit)
                    }
                    answer.get(3, TimeUnit.SECONDS) shouldBe Either.Right(1)
                    cache.stats().size shouldBe 0
                    cache.ahead() shouldBe 0
                    layer.writes.peek() shouldBe emptyList()
                }
        }
    }
}

private class CallbackLayer : Layer<String, Int> {
    val writes = Shared(emptyList<Pair<String, Int>>())

    override fun read(key: String): Stored<Int>? = null

    override fun write(key: String, stored: Stored<Int>) {
        writes.update { it + (key to stored.value) }
    }

    override fun remove(key: String) = Unit

    override fun clear() = Unit
}
