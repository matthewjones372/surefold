package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContainExactly
import dev.surefold.assert.shouldThrow
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test

private const val RETRIES = 50

class AfterCommitTest {

    @Test
    fun `an effect runs after the step commits, and sees what it committed`() {
        val free = Shared(10)
        val seen = ConcurrentLinkedQueue<Int>()

        transact {
            free.update { it - 2 }
            afterCommit { seen.add(free.peek()) }
        }

        seen.toList() shouldContainExactly listOf(8)
    }

    @Test
    fun `a step that runs fifty times runs its effect once`() {
        val free = Shared(10)
        val runs = AtomicInteger()
        val effects = AtomicInteger()

        // Never alone: alone, the run below would wait on a commit that waits on it.
        transact(soloAfter = Int.MAX_VALUE) {
            val left = free.get()
            // Another step changes what this run read, so this run cannot commit.
            if (runs.incrementAndGet() <= RETRIES)
                Thread.ofVirtual().start { transact { free.update { it + 1 } } }.join()
            free.set(left - 1)
            afterCommit { effects.incrementAndGet() }
        }

        runs.get() shouldBe RETRIES + 1
        effects.get() shouldBe 1
    }

    @Test
    fun `a step that throws or fails runs no effect`() {
        val effects = AtomicInteger()

        shouldThrow<IllegalStateException> {
            transact {
                afterCommit { effects.incrementAndGet() }
                error("payment refused")
            }
        }
        val failed =
            attempt<String, Unit> {
                transact {
                    afterCommit { effects.incrementAndGet() }
                    fail("sold out")
                }
            }

        failed shouldBe Either.Left("sold out")
        effects.get() shouldBe 0
    }

    @Test
    fun `effects run in the order they were queued, a nested step's with the outer step's`() {
        val order = ConcurrentLinkedQueue<String>()

        transact {
            afterCommit { order.add("outer") }
            transact { afterCommit { order.add("nested") } }
            afterCommit { order.add("last") }
        }

        order.toList() shouldContainExactly listOf("outer", "nested", "last")
    }

    @Test
    fun `firstOf drops the effects of a branch that waited`() {
        val east = Shared(0)
        val west = Shared(5)
        val order = ConcurrentLinkedQueue<String>()

        transact {
            firstOf(
                {
                    afterCommit { order.add("east") }
                    if (east.get() == 0) waitForChange()
                },
                { afterCommit { order.add("west") } },
            )
        }

        order.toList() shouldContainExactly listOf("west")
        west.peek() shouldBe 5
    }

    @Test
    fun `an effect that throws keeps the commit, the rest still run, and the first throw comes back`() {
        val free = Shared(10)
        val ran = ConcurrentLinkedQueue<String>()

        val thrown =
            shouldThrow<IllegalStateException> {
                transact {
                    free.set(9)
                    afterCommit { error("first") }
                    afterCommit { ran.add("second") }
                    afterCommit { throw IllegalArgumentException("third") }
                }
            }

        thrown.message shouldBe "first"
        thrown.suppressed.map { it.message } shouldContainExactly listOf("third")
        ran.toList() shouldContainExactly listOf("second")
        free.peek() shouldBe 9
    }

    @Test
    fun `an effect that starts a step starts a new one`() {
        val free = Shared(10)
        val sold = Shared(0)

        transact {
            free.update { it - 1 }
            afterCommit { transact { sold.update { it + 1 } } }
        }

        sold.peek() shouldBe 1
    }
}
