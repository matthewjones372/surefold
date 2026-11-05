package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeSameInstanceAs
import dev.surefold.assert.shouldThrow
import dev.surefold.assert.withClue
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
                uninterruptible {
                    attempts++
                    throw failure
                }
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
                uninterruptible(
                    onInterrupted = {
                        observed += it
                        throw failed
                    },
                ) {
                    throw stopped
                }
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
            onThrow(
                {
                    seen = it
                    throw observer
                },
            ) {
                throw original
            }
        } shouldBeSameInstanceAs original
        seen shouldBeSameInstanceAs original
        original.suppressed.toList() shouldBe listOf(observer)
    }

    @Test
    fun `onThrow sees interruption cancellation fatal and foreign failure unwinding without changing them`() {
        val failures =
            listOf(
                InterruptedException("stop"),
                CancellationException("cancel"),
                LinkageError("link"),
                IllegalStateException("unexpected"),
            )
        for (failure in failures) {
            var seen: Throwable? = null
            shouldThrow<Throwable> { onThrow({ seen = it }) { throw failure } } shouldBeSameInstanceAs failure
            seen shouldBeSameInstanceAs failure
        }
        var observed = false
        attempt<String, Unit> {
            onThrow({ observed = true }) { fail("refused") }
        } shouldBe Either.Left("refused")
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

    @Test
    fun `draining lets a real wait finish while an interrupt is pending`() {
        resourceScope {
            onRelease { Thread.interrupted() }
            Thread.currentThread().interrupt()
            draining {
                Thread.sleep(1)
                "slept"
            } shouldBe "slept"
            Thread.interrupted() shouldBe true
        }
    }

    @Test
    fun `draining rethrows a failing cleanup unchanged and restores prior interruption`() {
        resourceScope {
            onRelease { Thread.interrupted() }
            val failure = IllegalStateException("cleanup")
            Thread.currentThread().interrupt()
            shouldThrow<IllegalStateException> { draining { throw failure } } shouldBeSameInstanceAs failure
            Thread.interrupted() shouldBe true
        }
    }

    @Test
    fun `uninterruptible clears the flag before each retry so a checkpoint cannot spin`() {
        resourceScope {
            onRelease { Thread.interrupted() }
            var attempts = 0
            uninterruptible {
                attempts++
                withClue("attempt $attempts retried a wait that kept failing on a flag nobody cleared") {
                    (attempts <= 2) shouldBe true
                }
                if (attempts == 1) Thread.currentThread().interrupt()
                checkInterrupted()
                "done"
            } shouldBe "done"
            attempts shouldBe 2
            Thread.interrupted() shouldBe true
        }
    }

    @Test
    fun `uninterruptible finishes a real wait interrupted on a virtual thread and hands the interrupt back`() {
        val gate = CountDownLatch(1)
        val observed = CountDownLatch(1)
        var answer: String? = null
        var virtual = false
        var restored = false
        val waiter =
            Thread.ofVirtual().start {
                virtual = Thread.currentThread().isVirtual
                answer =
                    uninterruptible(onInterrupted = { observed.countDown() }) {
                        gate.await()
                        "joined"
                    }
                restored = Thread.interrupted()
            }
        try {
            var tries = 0
            while (!observed.await(10, TimeUnit.MILLISECONDS) && tries++ < 500) waiter.interrupt()
            withClue("the waiter never observed an interrupted wait") { observed.count shouldBe 0L }
            waiter.isAlive shouldBe true
        } finally {
            gate.countDown()
            waiter.join()
        }
        virtual shouldBe true
        answer shouldBe "joined"
        restored shouldBe true
    }

    @Test
    fun `uninterruptible clears a pending interrupt on entry so the wait runs once`() {
        resourceScope {
            onRelease { Thread.interrupted() }
            var attempts = 0
            var observed = 0
            Thread.currentThread().interrupt()
            uninterruptible(onInterrupted = { observed++ }) {
                attempts++
                Thread.sleep(1)
                "waited"
            } shouldBe "waited"
            attempts shouldBe 1
            observed shouldBe 0
            Thread.interrupted() shouldBe true
        }
    }

    @Test
    fun `a throw observer that rethrows its cause surfaces the original without self-suppression`() {
        val original = IllegalStateException("original")
        shouldThrow<IllegalStateException> {
            onThrow({ throw it }) { throw original }
        } shouldBeSameInstanceAs original
        original.suppressed.toList() shouldBe emptyList()
    }

    @Test
    fun `an interrupted throw observer is suppressed onto the original and the interrupt is restored`() {
        resourceScope {
            onRelease { Thread.interrupted() }
            val original = IllegalStateException("original")
            val stopped = InterruptedException("cleanup interrupted")
            shouldThrow<IllegalStateException> {
                onThrow({ throw stopped }) { throw original }
            } shouldBeSameInstanceAs original
            original.suppressed.toList() shouldBe listOf<Throwable>(stopped)
            Thread.interrupted() shouldBe true
        }
    }

    @Test
    fun `an observer failure during fail unwinding is retained on the same signal`() {
        val observer = IllegalStateException("observer")
        val answer =
            attempt<String, Unit> {
                val signal = shouldThrow<Throwable> { onThrow({ throw observer }) { fail("refused") } }
                signal.suppressed.toList() shouldBe listOf<Throwable>(observer)
                throw signal
            }
        answer shouldBe Either.Left("refused")
    }
}

private fun leave(): String = draining {
    Thread.currentThread().interrupt()
    return "left"
}

private fun leaveWait(): String = uninterruptible {
    return "done"
}

private fun leaveObserved(): String =
    onThrow({ error("unexpected observer") }) {
        return "returned"
    }
