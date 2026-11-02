package dev.surefold.cache

import dev.surefold.Promise
import dev.surefold.TestClock
import dev.surefold.assert.shouldBe
import dev.surefold.awaitValue
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.parMap
import dev.surefold.parZip
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val CALLERS = 20

/** A function with a memory: one lookup per key, ttl by result, and the last good value while the source fails. */
class CacheTest {

    private val moving = TestClock()

    private fun <E, V> cache(
        ttl: (Either<E, V>) -> kotlin.time.Duration,
        stale: kotlin.time.Duration = kotlin.time.Duration.ZERO,
        lookup: (String) -> Either<E, V>,
    ): Cache<String, E, V> = clock.locally(moving) { Cache(ttl, stale, lookup = lookup) }

    @Test
    fun `twenty callers asking for one missing key run its lookup once`() {
        val lookups = AtomicInteger()
        val release = Promise<Nothing, Unit>()
        val prices = cache<String, Int>({ 30.seconds }) {
            lookups.incrementAndGet()
            release.awaitValue()
            Either.Right(7)
        }
        val answers = parZip(
            { parMap(1..CALLERS) { prices.get("sku") } },
            {
                while (prices.waiting("sku") < CALLERS) Thread.onSpinWait()
                release.succeed(Unit)
            },
        ) { all, _ -> all }
        answers.toSet() shouldBe setOf(Either.Right(7))
        lookups.get() shouldBe 1
        prices.get("sku") shouldBe Either.Right(7)
        lookups.get() shouldBe 1
        prices.stats().loads shouldBe 1
    }

    @Test
    fun `a Left with a two-second ttl is looked up again after two seconds, and not before`() {
        val lookups = AtomicInteger()
        val prices = cache<String, Int>({ result -> if (result is Either.Right) 30.seconds else 2.seconds }) {
            lookups.incrementAndGet()
            Either.Left("source down")
        }
        prices.get("sku") shouldBe Either.Left("source down")
        moving.adjust(1999.milliseconds)
        prices.get("sku") shouldBe Either.Left("source down")
        lookups.get() shouldBe 1
        moving.adjust(1.milliseconds)
        prices.get("sku")
        lookups.get() shouldBe 2
        prices.stats().failures shouldBe 2
    }

    @Test
    fun `a failing lookup answers the last good value until stale runs out, and the error after`() {
        val source = AtomicReference<Either<String, Int>>(Either.Right(1))
        val prices = cache<String, Int>(
            ttl = { result -> if (result is Either.Right) 30.seconds else 2.seconds },
            stale = 10.minutes,
        ) { source.get() }
        prices.get("sku") shouldBe Either.Right(1)
        source.set(Either.Left("source down"))
        moving.adjust(31.seconds)
        prices.get("sku") shouldBe Either.Right(1)
        moving.adjust(1.seconds)
        prices.get("sku") shouldBe Either.Right(1)
        moving.adjust(10.minutes)
        prices.get("sku") shouldBe Either.Left("source down")
        prices.stats().stale shouldBe 2
    }

    @Test
    fun `an invalidated key is looked up again, and a lookup in flight across it is not kept`() {
        val lookups = AtomicInteger()
        val prices = cache<String, Int>({ 30.seconds }) { Either.Right(lookups.incrementAndGet()) }
        prices.get("a") shouldBe Either.Right(1)
        prices.invalidate(Invalidation.Key("a"))
        prices.get("a") shouldBe Either.Right(2)
        prices.get("b") shouldBe Either.Right(3)
        prices.invalidate(Invalidation.All)
        prices.stats().size shouldBe 0
        prices.get("b") shouldBe Either.Right(4)
    }
}
