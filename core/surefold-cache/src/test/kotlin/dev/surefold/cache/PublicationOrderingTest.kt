package dev.surefold.cache

import dev.surefold.Promise
import dev.surefold.Shared
import dev.surefold.assert.shouldBe
import dev.surefold.awaitValue
import dev.surefold.fail.Either
import dev.surefold.surefoldFuture
import dev.surefold.test.eventually
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class PublicationOrderingTest {
    @Test
    fun `an admitted write finishes before conflicting invalidation removes it`() {
        listOf(Invalidation.Key("a"), Invalidation.All).forEach { what ->
            val layer = OrderingLayer()
            val write = Gate()
            layer.writing = { key -> if (key == "a") write.pause() }
            val calls = Shared(0)
            Cache<String, Nothing, Int>({ 1.minutes }, layers = listOf(layer)) {
                Either.Right(calls.modify { count -> count + 1 to count + 1 })
            }.use { cache ->
                val getting = surefoldFuture { cache.get("a") }
                write.entered.awaitValue(3.seconds) shouldBe Unit
                val runner = Promise<Nothing, Thread>()
                val invalidating = surefoldFuture {
                    runner.succeed(Thread.currentThread())
                    cache.invalidate(what)
                }
                try {
                    parkedOrEnded(runner.awaitValue())
                    layer.removals.peek() shouldBe emptyList()
                } finally {
                    write.release.succeed(Unit)
                    getting.get(3, TimeUnit.SECONDS)
                    invalidating.get(3, TimeUnit.SECONDS)
                }
                layer.held.peek() shouldBe emptyMap()
                cache.stats().size shouldBe 0
                cache.get("a") shouldBe Either.Right(2)
            }
        }
    }

    @Test
    fun `a layer read starting during removal cannot readmit the old record`() {
        val layer = OrderingLayer()
        layer.held.update { mapOf("a" to Stored(1, Instant.EPOCH, Instant.MAX)) }
        val removal = Gate()
        layer.removing = { removal.pause() }
        val calls = Shared(0)
        Cache<String, Nothing, Int>({ 1.minutes }, layers = listOf(layer)) {
            calls.update { count -> count + 1 }
            Either.Right(2)
        }.use { cache ->
            val invalidating = surefoldFuture { cache.invalidate(Invalidation.Key("a")) }
            removal.entered.awaitValue(3.seconds) shouldBe Unit
            val runner = Promise<Nothing, Thread>()
            val getting = surefoldFuture {
                runner.succeed(Thread.currentThread())
                cache.get("a")
            }
            try {
                parkedOrEnded(runner.awaitValue())
                layer.reads.peek() shouldBe emptyList()
            } finally {
                removal.release.succeed(Unit)
                invalidating.get(3, TimeUnit.SECONDS)
            }
            getting.get(3, TimeUnit.SECONDS) shouldBe Either.Right(2)
            cache.get("a") shouldBe Either.Right(2)
            calls.peek() shouldBe 1
        }
    }

    @Test
    fun `an unrelated key reaches its layer while another key write is blocked`() {
        val layer = OrderingLayer()
        val write = Gate()
        layer.writing = { key -> if (key == "a") write.pause() }
        Cache<String, Nothing, Int>({ 1.minutes }, layers = listOf(layer)) { Either.Right(1) }.use { cache ->
            val first = surefoldFuture { cache.get("a") }
            try {
                write.entered.awaitValue(3.seconds) shouldBe Unit
                surefoldFuture { cache.get("b") }.get(3, TimeUnit.SECONDS) shouldBe Either.Right(1)
                layer.held.peek().keys shouldBe setOf("b")
            } finally {
                write.release.succeed(Unit)
                first.get(3, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `All holds later keys behind earlier keys even when its waiting caller is canceled`() {
        val layer = OrderingLayer()
        val write = Gate()
        layer.writing = { key -> if (key == "a") write.pause() }
        Cache<String, Nothing, Int>({ 1.minutes }, layers = listOf(layer)) { Either.Right(1) }.use { cache ->
            val first = surefoldFuture { cache.get("a") }
            write.entered.awaitValue(3.seconds) shouldBe Unit
            val middleThread = Promise<Nothing, Thread>()
            val ended = Promise<Nothing, Boolean>()
            val middle = surefoldFuture {
                middleThread.succeed(Thread.currentThread())
                try {
                    cache.invalidate(Invalidation.All)
                } finally {
                    ended.succeed(Thread.currentThread().isInterrupted)
                }
            }
            val runner = middleThread.awaitValue()
            parkedOrEnded(runner)
            repeat(3) { middle.cancel(true) }
            val lastThread = Promise<Nothing, Thread>()
            val last = surefoldFuture {
                lastThread.succeed(Thread.currentThread())
                cache.invalidate(Invalidation.Key("b"))
            }
            try {
                parkedOrEnded(lastThread.awaitValue())
                layer.removals.peek() shouldBe emptyList()
            } finally {
                write.release.succeed(Unit)
                first.get(3, TimeUnit.SECONDS)
                last.get(3, TimeUnit.SECONDS)
                eventually(3.seconds) { runner.isAlive shouldBe false }
            }
            ended.awaitValue(3.seconds) shouldBe true
            layer.removals.peek() shouldBe listOf("b")
        }
    }
}

private class Gate {
    val entered = Promise<Nothing, Unit>()
    val release = Promise<Nothing, Unit>()

    fun pause() {
        entered.succeed(Unit)
        release.awaitValue()
    }
}

private class OrderingLayer : Layer<String, Int> {
    val held = Shared(emptyMap<String, Stored<Int>>())
    val reads = Shared(emptyList<String>())
    val removals = Shared(emptyList<String>())
    var writing: (String) -> Unit = {}
    var removing: () -> Unit = {}

    override fun read(key: String): Stored<Int>? {
        reads.update { it + key }
        return held.peek()[key]
    }

    override fun write(key: String, stored: Stored<Int>) {
        writing(key)
        held.update { it + (key to stored) }
    }

    override fun remove(key: String) {
        removing()
        removals.update { it + key }
        held.update { it - key }
    }

    override fun clear() {
        removing()
        removals.update { it + "all" }
        held.update { emptyMap() }
    }
}

private fun parkedOrEnded(thread: Thread) = eventually(3.seconds) {
    (thread.state == Thread.State.WAITING || thread.state == Thread.State.TIMED_WAITING || !thread.isAlive) shouldBe
        true
}
