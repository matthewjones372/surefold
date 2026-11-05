package dev.surefold.cache

import dev.surefold.TestClock
import dev.surefold.assert.shouldBe
import dev.surefold.clock
import dev.surefold.fail.Either
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

/** A store every instance shares, faked in memory: it can be told to fail, and it records what reached it. */
private class Shared<V> : Layer<String, V> {
    val held = ConcurrentHashMap<String, Stored<V>>()
    val removed = mutableListOf<String>()

    @Volatile var down = false

    override fun read(key: String): Stored<V>? = up { held[key] }

    override fun write(key: String, stored: Stored<V>) = up { held[key] = stored }

    override fun remove(key: String) = up {
        removed += key
        held.remove(key)
        Unit
    }

    override fun clear() = up { held.clear() }

    private fun <A> up(block: () -> A): A = if (down) error("the shared store is down") else block()
}

/** Reads go down the layers and values come back up; failures stay near; a layer that fails is a miss. */
class LayersTest {

    private val moving = TestClock()

    private fun cache(
        far: Layer<String, Int>,
        ttl: (Either<String, Int>) -> Duration = { 30.seconds },
        lookup: (String) -> Either<String, Int>,
    ): Cache<String, String, Int> = clock.locally(moving) { Cache(ttl, layers = listOf(far), lookup = lookup) }

    @Test
    fun `a hit in the far layer fills memory with its original expiry, without a lookup`() {
        val far = Shared<Int>()
        far.held["sku"] = Stored(7, Instant.EPOCH, Instant.EPOCH.plusSeconds(10))
        val lookups = AtomicInteger()
        val prices = cache(far) { Either.Right(lookups.incrementAndGet()) }
        prices.get("sku") shouldBe Either.Right(7)
        far.held.clear()
        moving.adjust(9.seconds)
        prices.get("sku") shouldBe Either.Right(7)
        lookups.get() shouldBe 0
        moving.adjust(1.seconds)
        prices.get("sku") shouldBe Either.Right(1)
        prices.stats().layerHits shouldBe 1
    }

    @Test
    fun `a good value looked up reaches the far layer, and a failure never does`() {
        val far = Shared<Int>()
        val prices = cache(far) { key -> if (key == "ok") Either.Right(1) else Either.Left("down") }
        prices.get("ok") shouldBe Either.Right(1)
        far.held["ok"]?.value shouldBe 1
        prices.get("bad") shouldBe Either.Left("down")
        far.held.containsKey("bad") shouldBe false
    }

    @Test
    fun `a far layer that throws is a miss, and the lookup still answers`() {
        val far = Shared<Int>().also { it.down = true }
        val prices = cache(far) { Either.Right(5) }
        prices.get("sku") shouldBe Either.Right(5)
        (prices.stats().layerFailures >= 2) shouldBe true
    }

    @Test
    fun `invalidate removes from the far layer before memory`() {
        val far = Shared<Int>()
        val lookups = AtomicInteger()
        val prices = cache(far) { Either.Right(lookups.incrementAndGet()) }
        prices.get("sku") shouldBe Either.Right(1)
        prices.invalidate(Invalidation.Key("sku"))
        far.removed shouldBe listOf("sku")
        prices.get("sku") shouldBe Either.Right(2)
        prices.invalidate(Invalidation.All)
        far.held.isEmpty() shouldBe true
    }
}
