package dev.surefold.cache

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
import dev.surefold.test.eventually
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class EffectBoundariesTest {
    @Test
    fun `All waits for earlier keys and holds later reads until physical removal finishes`() {
        val layer = BoundaryLayer()
        val write = BoundaryGate()
        val remove = BoundaryGate()
        layer.writing = { key -> if (key == "a") write.pause() }
        layer.removing = { remove.pause() }
        Cache<String, Nothing, Int>({ 1.minutes }, layers = listOf(layer)) { Either.Right(1) }.use { cache ->
            val first = surefoldFuture { cache.get("a") }
            write.entered.awaitValue(3.seconds) shouldBe Unit
            val middleRunner = Promise<Nothing, Thread>()
            val middle = surefoldFuture {
                middleRunner.succeed(Thread.currentThread())
                cache.invalidate(Invalidation.All)
            }
            boundaryParked(middleRunner.awaitValue())
            val last = surefoldFuture { cache.get("b") }
            try {
                layer.reads.peek() shouldBe listOf("a")
                write.release.succeed(Unit)
                remove.entered.awaitValue(3.seconds) shouldBe Unit
                layer.reads.peek() shouldBe listOf("a")
            } finally {
                write.release.succeed(Unit)
                remove.release.succeed(Unit)
                first.get(3, TimeUnit.SECONDS)
                middle.get(3, TimeUnit.SECONDS)
            }
            last.get(3, TimeUnit.SECONDS) shouldBe Either.Right(1)
            layer.held.peek().keys shouldBe setOf("b")
        }
    }

    @Test
    fun `an already admitted unrelated write can finish after invalidation advances generation`() {
        val layer = BoundaryLayer()
        val write = BoundaryGate()
        layer.writing = { write.pause() }
        Cache<String, Nothing, Int>({ 1.minutes }, layers = listOf(layer)) { Either.Right(1) }.use { cache ->
            val getting = surefoldFuture { cache.get("b") }
            try {
                write.entered.awaitValue(3.seconds) shouldBe Unit
                surefoldFuture { cache.invalidate(Invalidation.Key("a")) }.get(3, TimeUnit.SECONDS)
            } finally {
                write.release.succeed(Unit)
            }
            getting.get(3, TimeUnit.SECONDS) shouldBe Either.Right(1)
            layer.held.peek().keys shouldBe setOf("b")
            cache.get("b") shouldBe Either.Right(1)
            cache.stats().loads shouldBe 1
        }
    }

    @Test
    fun `failure of background admission metrics retires the surrounding schedule phase`() {
        listOf(false, true).forEach { histogram ->
            val moving = TestClock()
            val layer = BoundaryLayer()
            val marker = IllegalStateException("background admission defect")
            val cache = clock.locally(moving) {
                Cache<String, Nothing, Int>(
                    { 1.minutes }, layers = listOf(layer), refreshing = Schedule.spaced(10.seconds),
                ) { Either.Right(1) }
            }
            cache.use {
                metrics.locally(EffectMetrics("surefold.cache.backgrounds", marker, histogram)) {
                    shouldThrow<IllegalStateException> { cache.get("a") } shouldBe marker
                }
                surefoldFuture { cache.invalidate(Invalidation.Key("a")) }.get(3, TimeUnit.SECONDS)
                cache.stats().size shouldBe 0
                layer.held.peek() shouldBe emptyMap()
                moving.sleepers() shouldBe 0
                cache.get("b") shouldBe Either.Right(1)
                eventually(3.seconds) { moving.sleepers() shouldBe 1 }
            }
        }
    }

    @Test
    fun `private coordination metrics cannot strand effects and layer body metrics remain active`() {
        val layer = BoundaryLayer()
        val marker = IllegalStateException("metrics defect")
        val body = Shared(0, "layer-body")
        layer.removing = { body.update { it + 1 } }
        Cache<String, Nothing, Int>({ 1.minutes }, layers = listOf(layer)) { Either.Right(1) }.use { cache ->
            cache.get("a")
            metrics.locally(EffectMetrics("promise", marker)) { cache.invalidate(Invalidation.Key("a")) }
            body.peek() shouldBe 1
            metrics.locally(EffectMetrics("layer-body", marker)) { cache.invalidate(Invalidation.Key("b")) }
            body.peek() shouldBe 2
            cache.stats().layerFailures shouldBe 1
            surefoldFuture { cache.invalidate(Invalidation.All) }.get(3, TimeUnit.SECONDS)
            body.peek() shouldBe 3
        }
    }

    @Test
    fun `schedule decisions may get another key and invalidate before their effects are admitted`() {
        val entered = Shared(false)
        val decisions = Shared(0)
        lateinit var cache: Cache<String, Nothing, Int>
        val schedule = Schedule.spaced<Either<Nothing, Int>>(10.seconds).doWhile { _, _ ->
            decisions.update { it + 1 }
            if (entered.modify { true to !it }) {
                cache.get("b") shouldBe Either.Right(1)
                cache.invalidate(Invalidation.Key("a"))
            }
            true
        }
        cache = clock.locally(TestClock()) { Cache({ 1.minutes }, refreshing = schedule) { Either.Right(1) } }
        cache.use {
            cache.get("a") shouldBe Either.Right(1)
            decisions.peek() shouldBe 2
            cache.stats().size shouldBe 1
            cache.ahead() shouldBe 1
        }
    }

    @Test
    fun `invalidation during the first layer write prevents obsolete writes to the next layer`() {
        val first = BoundaryLayer()
        val last = BoundaryLayer()
        val write = BoundaryGate()
        first.writing = { write.pause() }
        Cache<String, Nothing, Int>({ 1.minutes }, layers = listOf(first, last)) { Either.Right(1) }.use { cache ->
            val getting = surefoldFuture { cache.get("a") }
            try {
                write.entered.awaitValue(3.seconds) shouldBe Unit
                cache.invalidate(Invalidation.Key("b"))
            } finally {
                write.release.succeed(Unit)
            }
            getting.get(3, TimeUnit.SECONDS) shouldBe Either.Right(1)
            first.held.peek().keys shouldBe setOf("a")
            last.held.peek() shouldBe emptyMap()
        }
    }

    @Test
    fun `ordinary close during admitted layer write returns and refuses subsequent layer and schedule effects`() {
        val first = BoundaryLayer()
        val last = BoundaryLayer()
        val write = BoundaryGate()
        first.writing = { write.resist() }
        val cache = Cache<String, Nothing, Int>(
            { 1.minutes }, layers = listOf(first, last), refreshing = Schedule.spaced(10.seconds),
        ) { Either.Right(1) }
        val getting = surefoldFuture { cache.get("a") }
        try {
            write.entered.awaitValue(3.seconds) shouldBe Unit
            surefoldFuture { cache.close() }.get(3, TimeUnit.SECONDS)
            getting.isDone shouldBe false
        } finally {
            write.release.succeed(Unit)
            getting.get(3, TimeUnit.SECONDS)
            cache.close()
        }
        first.held.peek().keys shouldBe setOf("a")
        last.held.peek() shouldBe emptyMap()
        cache.ahead() shouldBe 0
    }

    @Test
    fun `eviction may close its cache before later publication effects`() {
        val layer = BoundaryLayer()
        val gone = Promise<Nothing, Unit>()
        lateinit var cache: Cache<String, Nothing, Int>
        cache = Cache({ 1.minutes }, layers = listOf(layer), onEvict = { _, _ ->
            cache.close()
            gone.succeed(Unit)
        }) { Either.Right(1) }
        try {
            cache.get("a")
            cache.refresh("a")
            gone.awaitValue(3.seconds) shouldBe Unit
            eventually(3.seconds) { cache.waiting("a") shouldBe 0 }
            layer.writes.peek() shouldBe 1
        } finally {
            cache.close()
        }
    }

    @Test
    fun `ordinary close during an admitted layer read refuses its later readmission`() {
        val near = BoundaryLayer()
        val far = BoundaryLayer()
        val read = BoundaryGate()
        far.held.update { mapOf("a" to Stored(1, Instant.EPOCH, Instant.MAX)) }
        far.reading = { read.resist() }
        val cache = Cache<String, Nothing, Int>({ 1.minutes }, layers = listOf(near, far)) { Either.Right(2) }
        val getting = surefoldFuture { cache.get("a") }
        try {
            read.entered.awaitValue(3.seconds) shouldBe Unit
            surefoldFuture { cache.close() }.get(3, TimeUnit.SECONDS)
            getting.isDone shouldBe false
        } finally {
            read.release.succeed(Unit)
        }
        getting.get(3, TimeUnit.SECONDS) shouldBe Either.Right(1)
        cache.stats().size shouldBe 0
        cache.stats().loads shouldBe 0
        near.writes.peek() shouldBe 0
        cache.close()
    }

    @Test
    fun `a released memory reservation retains callback lifetime ownership through ordinary close`() {
        val notification = BoundaryGate()
        val cache = Cache<String, Nothing, Int>({ 1.minutes }, onEvict = { _, _ -> notification.pause() }) {
            Either.Right(1)
        }
        cache.get("a")
        val invalidating = surefoldFuture { cache.invalidate(Invalidation.All) }
        notification.entered.awaitValue(3.seconds) shouldBe Unit
        val owned = ownedEffects(cache).single()
        try {
            (owned.first.peek() != null) shouldBe true
            owned.second.peek() shouldBe null
            surefoldFuture { cache.close() }.get(3, TimeUnit.SECONDS)
            ownedEffects(cache).size shouldBe 1
            invalidating.isDone shouldBe false
        } finally {
            notification.release.succeed(Unit)
            invalidating.get(3, TimeUnit.SECONDS)
            cache.close()
        }
        (owned.second.peek() != null) shouldBe true
        ownedEffects(cache) shouldBe emptyList()
    }

    @Test
    fun `ordinary close during a schedule decision refuses its later installation`() {
        val deciding = BoundaryGate()
        val schedule = Schedule.spaced<Either<Nothing, Int>>(10.seconds).doWhile { _, _ ->
            deciding.resist()
            true
        }
        val cache = Cache<String, Nothing, Int>({ 1.minutes }, refreshing = schedule) { Either.Right(1) }
        val getting = surefoldFuture { cache.get("a") }
        try {
            deciding.entered.awaitValue(3.seconds) shouldBe Unit
            surefoldFuture { cache.close() }.get(3, TimeUnit.SECONDS)
            getting.isDone shouldBe false
        } finally {
            deciding.release.succeed(Unit)
        }
        getting.get(3, TimeUnit.SECONDS) shouldBe Either.Right(1)
        cache.ahead() shouldBe 0
        cache.close()
    }

    @Test
    fun `invalidation attempts farthest layers first and finishes memory after a layer fails`() {
        val near = BoundaryLayer()
        val far = BoundaryLayer()
        val order = Shared(emptyList<String>())
        near.removing = { order.update { it + "near" } }
        far.removing = {
            order.update { it + "far" }
            error("far unavailable")
        }
        Cache<String, Nothing, Int>({ 1.minutes }, layers = listOf(near, far), onEvict = { _, _ ->
            order.update { it + "memory" }
        }) { Either.Right(1) }.use { cache ->
            cache.get("a")
            cache.invalidate(Invalidation.Key("a"))
            order.peek() shouldBe listOf("far", "near", "memory")
            cache.stats().size shouldBe 0
            cache.stats().layerFailures shouldBe 1
            near.held.peek() shouldBe emptyMap()
            far.held.peek().keys shouldBe setOf("a")
        }
    }

    @Test
    fun `layer hit eviction invalidation prevents subsequent nearer layer readmission`() {
        val near = BoundaryLayer()
        val far = BoundaryLayer()
        val moving = TestClock()
        val once = Shared(false)
        far.held.update { mapOf("a" to Stored(1, Instant.EPOCH, Instant.EPOCH.plusSeconds(10))) }
        lateinit var cache: Cache<String, Nothing, Int>
        cache = clock.locally(moving) {
            Cache({ 1.minutes }, layers = listOf(near, far), onEvict = { _, _ ->
                if (once.modify { true to !it }) cache.invalidate(Invalidation.All)
            }) { Either.Right(3) }
        }
        cache.use {
            cache.get("a") shouldBe Either.Right(1)
            moving.adjust(20.seconds)
            near.held.update { emptyMap() }
            far.held.update { mapOf("a" to Stored(2, Instant.EPOCH, Instant.EPOCH.plusSeconds(100))) }
            cache.get("a") shouldBe Either.Right(2)
            near.writes.peek() shouldBe 1
            near.held.peek() shouldBe emptyMap()
            far.held.peek() shouldBe emptyMap()
            cache.stats().size shouldBe 0
        }
    }
}

private class BoundaryGate {
    val entered = Promise<Nothing, Unit>()
    val release = Promise<Nothing, Unit>()

    fun pause() {
        entered.succeed(Unit)
        release.awaitValue()
    }

    fun resist() {
        entered.succeed(Unit)
        while (release.peek() == null) {
            try {
                release.awaitValue()
            } catch (_: InterruptedException) {
                // Ordinary close does not await this layer's cleanup.
            }
        }
    }
}

private class BoundaryLayer : Layer<String, Int> {
    val held = Shared(emptyMap<String, Stored<Int>>())
    val reads = Shared(emptyList<String>())
    val writes = Shared(0)
    var reading: () -> Unit = {}
    var writing: (String) -> Unit = {}
    var removing: () -> Unit = {}

    override fun read(key: String): Stored<Int>? {
        reads.update { it + key }
        val stored = held.peek()[key]
        reading()
        return stored
    }

    override fun write(key: String, stored: Stored<Int>) {
        writing(key)
        writes.update { it + 1 }
        held.update { it + (key to stored) }
    }

    override fun remove(key: String) {
        removing()
        held.update { it - key }
    }

    override fun clear() {
        removing()
        held.update { emptyMap() }
    }
}

private class EffectMetrics(
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

private fun boundaryParked(thread: Thread) = eventually(3.seconds) {
    (thread.state == Thread.State.WAITING || !thread.isAlive) shouldBe true
}

private fun ownedEffects(cache: Cache<*, *, *>): List<Pair<Promise<*, *>, Promise<*, *>>> {
    val admission = effectField(cache, "admission") as ReentrantLock
    return admission.withLock {
        val operations = effectField(cache, "operations") as List<*>
        operations.map { operation ->
            val held = checkNotNull(operation)
            val released = effectField(held, "released") as Promise<*, *>
            val finished = effectField(held, "finished") as Promise<*, *>
            released to finished
        }
    }
}

private fun effectField(value: Any, name: String): Any? =
    value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
