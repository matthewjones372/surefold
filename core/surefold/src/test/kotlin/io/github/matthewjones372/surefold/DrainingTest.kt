package io.github.matthewjones372.surefold

import io.github.matthewjones372.surefold.assert.shouldBe
import io.github.matthewjones372.surefold.assert.shouldBeSameInstanceAs
import io.github.matthewjones372.surefold.assert.shouldThrow
import org.junit.jupiter.api.Test

class DrainingTest {
    @Test
    fun `draining clears an existing interrupt and restores it on return`() {
        resourceScope {
            onRelease { Thread.interrupted() }
            Thread.currentThread().interrupt()
            draining { Thread.currentThread().isInterrupted } shouldBe false
            Thread.interrupted() shouldBe true
        }
    }

    @Test
    fun `draining restores a thrown interrupt by identity`() {
        resourceScope {
            onRelease { Thread.interrupted() }
            val stopped = InterruptedException("stopped")
            shouldThrow<InterruptedException> { draining { throw stopped } } shouldBeSameInstanceAs stopped
            Thread.interrupted() shouldBe true
        }
    }

    @Test
    fun `draining supports non-local return and restores a newly raised interrupt`() {
        resourceScope {
            onRelease { Thread.interrupted() }
            leave() shouldBe "left"
            Thread.interrupted() shouldBe true
        }
    }

    @Test
    fun `uninterruptible retries only interrupted waits and restores the flag`() {
        resourceScope {
            onRelease { Thread.interrupted() }
            var attempts = 0
            uninterruptible {
                attempts++
                if (attempts < 3) throw InterruptedException("waiting")
                "done"
            } shouldBe "done"
            attempts shouldBe 3
            Thread.interrupted() shouldBe true
        }
    }

    @Test
    fun `uninterruptible propagates ordinary failure once and restores prior interruption`() {
        resourceScope {
            onRelease { Thread.interrupted() }
            val failure = IllegalStateException("failed")
            var attempts = 0
            Thread.currentThread().interrupt()
            shouldThrow<IllegalStateException> {
                uninterruptible { attempts++; throw failure }
            } shouldBeSameInstanceAs failure
            attempts shouldBe 1
            Thread.interrupted() shouldBe true
        }
    }

    @Test
    fun `uninterruptible supports non-local return`() {
        leaveWait() shouldBe "done"
    }

    @Test
    fun `interruption observers run once per retry and their failure propagates unchanged`() {
        resourceScope {
            onRelease { Thread.interrupted() }
            val observed = mutableListOf<InterruptedException>()
            val stopped = InterruptedException("waiting")
            val failed = IllegalStateException("observer")
            shouldThrow<IllegalStateException> {
                uninterruptible(onInterrupted = { observed += it; throw failed }) { throw stopped }
            } shouldBeSameInstanceAs failed
            observed shouldBe listOf(stopped)
            Thread.interrupted() shouldBe true
        }
    }

    @Test
    fun `onThrow observes the same cause and preserves it when its observer also fails`() {
        val original = IllegalStateException("original")
        val observer = IllegalArgumentException("observer")
        var seen: Throwable? = null
        shouldThrow<IllegalStateException> {
            onThrow({ seen = it; throw observer }) { throw original }
        } shouldBeSameInstanceAs original
        seen shouldBeSameInstanceAs original
        original.suppressed.toList() shouldBe listOf(observer)
    }

    @Test
    fun `onThrow sees interruption cancellation fatal and foreign failure unwinding without changing them`() {
        val failures = listOf(
            InterruptedException("stop"), java.util.concurrent.CancellationException("cancel"),
            LinkageError("link"), IllegalStateException("unexpected"),
        )
        for (failure in failures) {
            var seen: Throwable? = null
            shouldThrow<Throwable> { onThrow({ seen = it }) { throw failure } } shouldBeSameInstanceAs failure
            seen shouldBeSameInstanceAs failure
        }
        var observed = false
        io.github.matthewjones372.surefold.fail.attempt<String, Unit> {
            onThrow({ observed = true }) { fail("refused") }
        } shouldBe io.github.matthewjones372.surefold.fail.Either.Left("refused")
        observed shouldBe true
    }

    @Test
    fun `onThrow passes through successful and non-local returns without invoking its observer`() {
        onThrow({ error("unexpected observer") }) { 42 } shouldBe 42
        leaveObserved() shouldBe "returned"
    }

    @Test
    fun `cancellation checkpoints preserve an existing interrupt and allow ordinary work`() {
        resourceScope {
            onRelease { Thread.interrupted() }
            checkInterrupted()
            Thread.currentThread().interrupt()
            shouldThrow<InterruptedException> { checkInterrupted() }
            Thread.interrupted() shouldBe true
        }
    }
}

private fun leave(): String = draining {
    Thread.currentThread().interrupt()
    return "left"
}

private fun leaveWait(): String = uninterruptible { return "done" }

private fun leaveObserved(): String = onThrow({ error("unexpected observer") }) { return "returned" }
