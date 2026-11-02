package dev.surefold.cache

import dev.surefold.Promise
import dev.surefold.Schedule
import dev.surefold.TestClock
import dev.surefold.assert.shouldBe
import dev.surefold.awaitValue
import dev.surefold.clock
import dev.surefold.fail.Either
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Retry inside one lookup, refresh ahead of expiry for keys still read, and a close that stops them all. */
class SchedulesTest {

    private val moving = TestClock()

    private fun until(done: () -> Boolean) {
        while (!done()) Thread.onSpinWait()
    }

    @Test
    fun `a lookup failing twice then answering, under recurs(2), answers the value`() {
        val lookups = AtomicInteger()
        val prices = clock.locally(moving) {
            Cache<String, String, Int>({ 1.minutes }, retry = Schedule.recurs(2)) {
                if (lookups.incrementAndGet() < 3) Either.Left("busy") else Either.Right(7)
            }
        }
        prices.get("sku") shouldBe Either.Right(7)
        lookups.get() shouldBe 3
        prices.stats().loads shouldBe 3
        prices.close()
    }

    @Test
    fun `a key read in each window is looked up again at 25 and 50 seconds, and let go once nobody reads it`() {
        val lookups = AtomicInteger()
        val prices = clock.locally(moving) {
            Cache<String, Nothing, Int>({ 1.minutes }, refreshing = Schedule.spaced(25.seconds)) {
                Either.Right(lookups.incrementAndGet())
            }
        }
        prices.get("sku") shouldBe Either.Right(1)
        until { moving.sleepers() == 1 }
        moving.adjust(10.seconds)
        prices.get("sku")
        moving.adjust(15.seconds)
        until { lookups.get() == 2 && moving.sleepers() == 1 }
        moving.adjust(5.seconds)
        prices.get("sku") shouldBe Either.Right(2)
        moving.adjust(20.seconds)
        until { lookups.get() == 3 && moving.sleepers() == 1 }
        moving.adjust(25.seconds)
        until { prices.ahead() == 0 }
        lookups.get() shouldBe 3
        prices.close()
    }

    @Test
    fun `during a refresh get answers the old value, and a failed refresh keeps it`() {
        val source = AtomicReference<Either<String, Int>>(Either.Right(1))
        val release = Promise<Nothing, Unit>()
        val blocking = AtomicBoolean(false)
        val prices = clock.locally(moving) {
            Cache<String, String, Int>({ 1.minutes }) {
                if (blocking.get()) release.awaitValue()
                source.get()
            }
        }
        prices.get("sku") shouldBe Either.Right(1)
        blocking.set(true)
        source.set(Either.Left("down"))
        prices.refresh("sku")
        until { prices.stats().loads == 2L }
        prices.get("sku") shouldBe Either.Right(1)
        release.succeed(Unit)
        until { prices.stats().failures == 1L }
        prices.get("sku") shouldBe Either.Right(1)
        prices.close()
    }

    @Test
    fun `closing the cache stops a refresh that is blocked in its lookup`() {
        val stopped = Promise<Nothing, Boolean>()
        val started = AtomicBoolean(false)
        val prices = clock.locally(moving) {
            Cache<String, String, Int>({ 1.minutes }) {
                if (!started.compareAndSet(false, true)) {
                    try {
                        Promise<Nothing, Unit>().awaitValue()
                    } catch (_: InterruptedException) {
                        stopped.succeed(true)
                    }
                }
                Either.Right(1)
            }
        }
        prices.get("sku")
        prices.refresh("sku")
        until { prices.stats().loads == 2L }
        prices.close()
        stopped.awaitValue() shouldBe true
    }
}
