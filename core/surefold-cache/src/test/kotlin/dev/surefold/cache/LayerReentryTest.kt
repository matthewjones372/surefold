package dev.surefold.cache

import dev.surefold.Promise
import dev.surefold.Shared
import dev.surefold.assert.shouldBe
import dev.surefold.awaitValue
import dev.surefold.fail.Either
import dev.surefold.surefoldFuture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Timeout(20)
class LayerReentryTest {
    @Test
    fun `active direct and inherited layer calls reject owning cache operations before mutation`() {
        listOf(false, true).forEach { inherited ->
            listOf("read", "write", "remove", "clear").forEach { operation ->
                listOf("get", "refresh", "invalidate").forEach { nested -> verifyReentry(inherited, operation, nested) }
            }
        }
    }

    @Test
    fun `an inherited child may call the cache after its originating layer invocation finishes`() {
        val release = Promise<Nothing, Unit>()
        val child = Promise<Nothing, CompletableFuture<Either<Nothing, Int>>>()
        val layer = ReentrantLayer()
        val fired = Shared(false)
        Cache<String, Nothing, Int>({ 1.minutes }, layers = listOf(layer)) { Either.Right(1) }.use { cache ->
            layer.invoke = { operation ->
                if (operation == "read" && fired.modify { true to !it }) {
                    child.succeed(
                        surefoldFuture {
                            release.awaitValue()
                            cache.get("b")
                        },
                    )
                }
            }
            try {
                cache.get("a") shouldBe Either.Right(1)
            } finally {
                release.succeed(Unit)
            }
            child.awaitValue(3.seconds)?.get(3, TimeUnit.SECONDS) shouldBe Either.Right(1)
        }
    }

    @Test
    fun `a layer can read stats and another cache`() {
        val other = Cache<String, Nothing, Int>({ 1.minutes }) { Either.Right(2) }
        val layer = ReentrantLayer()
        Cache<String, Nothing, Int>({ 1.minutes }, layers = listOf(layer)) { Either.Right(1) }.use { cache ->
            layer.invoke = {
                (cache.stats().size >= 0) shouldBe true
                other.get("b") shouldBe Either.Right(2)
            }
            cache.get("a") shouldBe Either.Right(1)
        }
        other.close()
    }
}

private fun verifyReentry(inherited: Boolean, operation: String, nested: String) {
    val calls = Shared(emptyList<String>())
    val layer = ReentrantLayer()
    Cache<String, Nothing, Int>({ 1.minutes }, layers = listOf(layer)) { key ->
        calls.update { it + key }
        Either.Right(1)
    }.use { cache ->
        val fired = Shared(false)
        val rejected = Shared(0)
        layer.invoke = { kind ->
            if (kind == operation && fired.modify { true to !it }) {
                val before = cache.stats()
                val invoke = { attemptReentry(cache, nested, rejected) }
                if (inherited) surefoldFuture(invoke).get(3, TimeUnit.SECONDS) else invoke()
                cache.stats() shouldBe before
            }
        }
        cache.get("a") shouldBe Either.Right(1)
        when (operation) {
            "remove" -> cache.invalidate(Invalidation.Key("a"))
            "clear" -> cache.invalidate(Invalidation.All)
        }
        rejected.peek() shouldBe 1
        if (operation == "read" || operation == "write") cache.get("a") shouldBe Either.Right(1)
        calls.peek() shouldBe listOf("a")
        cache.get("b") shouldBe Either.Right(1)
    }
}

private fun attemptReentry(cache: Cache<String, Nothing, Int>, nested: String, rejected: Shared<Int>) {
    try {
        when (nested) {
            "get" -> cache.get("b")
            "refresh" -> cache.refresh("b")
            "invalidate" -> cache.invalidate(Invalidation.Key("b"))
        }
    } catch (_: IllegalStateException) {
        rejected.update { it + 1 }
    }
}

private class ReentrantLayer : Layer<String, Int> {
    var invoke: (String) -> Unit = {}

    override fun read(key: String): Stored<Int>? {
        invoke("read")
        return null
    }

    override fun write(key: String, stored: Stored<Int>) = invoke("write")

    override fun remove(key: String) = invoke("remove")

    override fun clear() = invoke("clear")
}
