package dev.surefold.cache

import dev.surefold.assert.shouldBe
import dev.surefold.fail.Either
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours

private const val HOT_READS = 100
private const val SCAN = 10_000

/** At most `capacity` keys, admitted by how often they are asked for, and every value that goes seen by onEvict. */
class AdmissionTest {

    @Test
    fun `a key read a hundred times survives a scan of ten thousand keys read once`() {
        val lookups = AtomicInteger()
        val prices = Cache<String, Nothing, String>({ 1.hours }, capacity = 100) { key ->
            lookups.incrementAndGet()
            Either.Right(key)
        }
        repeat(HOT_READS) { prices.get("hot") }
        (1..SCAN).forEach { prices.get("scan-$it") }
        val before = lookups.get()
        prices.get("hot") shouldBe Either.Right("hot")
        lookups.get() shouldBe before
        (prices.stats().size <= 100) shouldBe true
    }

    @Test
    fun `past capacity the cache holds no more than capacity keys`() {
        val prices = Cache<Int, Nothing, Int>({ 1.hours }, capacity = 10) { Either.Right(it) }
        (1..1_000).forEach { key -> repeat(3) { prices.get(key) } }
        prices.stats().size shouldBe 10
    }

    @Test
    fun `onEvict sees every value that goes, pushed out, replaced or invalidated, and none still held`() {
        val gone = ConcurrentLinkedQueue<Pair<Int, Int>>()
        val version = AtomicInteger()
        val prices =
            Cache<Int, Nothing, Int>({ 1.hours }, capacity = 2, onEvict = { key, value -> gone += key to value }) {
                Either.Right(version.incrementAndGet())
            }
        prices.get(1)
        prices.get(2)
        repeat(3) { prices.get(3) }
        prices.invalidate(Invalidation.Key(2))
        prices.invalidate(Invalidation.All)
        val held = gone.map { it.second }.toSet()
        held.size shouldBe gone.size
        prices.stats().evictions shouldBe gone.size.toLong()
        gone.size shouldBe 3
    }
}
