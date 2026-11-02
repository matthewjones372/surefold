package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldThrow
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

private const val BUYERS = 500

class WaitForChangeTest {

    // Each spin answers an interrupt, so the test timeout ends a wait that never comes rather than hanging the build.
    private fun spinUntil(done: () -> Boolean) {
        while (!done()) {
            if (Thread.interrupted()) throw InterruptedException()
            Thread.onSpinWait()
        }
    }

    private fun Shared<*>.awaitWaiters(count: Int) = spinUntil { waiters.size == count }

    private fun Thread.awaitParkedOn(shared: Shared<*>) =
        spinUntil { this in shared.waiters && state == Thread.State.WAITING }

    @Test
    fun `a waiting step runs again each time a value it read is committed, and finishes once it can`() {
        val free = Shared(0)
        val runs = AtomicInteger()

        val buyer = Thread.ofVirtual().start {
            transact {
                runs.incrementAndGet()
                val left = free.get()
                if (left < 2) waitForChange()
                free.set(left - 2)
            }
        }
        buyer.awaitParkedOn(free)
        transact { free.set(1) }
        spinUntil { runs.get() >= 2 }
        buyer.awaitParkedOn(free)

        transact { free.update { it + 1 } }
        buyer.join()

        runs.get() shouldBe 3
        free.peek() shouldBe 0
    }

    @Test
    fun `a waiting step waits only on what it read`() {
        val stalls = Shared(0)
        val circle = Shared(0)

        val buyer = Thread.ofVirtual().start {
            transact { if (stalls.get() == 0) waitForChange() }
        }
        buyer.awaitParkedOn(stalls)

        circle.waiters.size shouldBe 0
        transact { stalls.set(1) }
        buyer.join()
        stalls.waiters.size shouldBe 0
    }

    @Test
    fun `seats released one at a time reach every waiting buyer`() {
        val free = Shared(0)

        val buyers = List(BUYERS) {
            Thread.ofVirtual().start {
                transact {
                    val left = free.get()
                    if (left == 0) waitForChange()
                    free.set(left - 1)
                }
            }
        }
        repeat(BUYERS) {
            transact { free.update { it + 1 } }
            Thread.yield()
        }
        buyers.forEach { it.join() }

        free.peek() shouldBe 0
    }

    @Test
    fun `cancel ends a waiting step with nothing written`() {
        val free = Shared(0)
        val held = Shared(0)

        scope {
            val buyer = async {
                transact {
                    if (free.get() == 0) waitForChange()
                    held.set(1)
                }
            }
            free.awaitWaiters(1)
            buyer.cancel()
        }

        held.peek() shouldBe 0
        free.waiters.size shouldBe 0
    }

    @Test
    fun `waiting in a step that has read nothing is refused`() {
        val thrown = shouldThrow<IllegalStateException> { transact { waitForChange() } }

        thrown.message.orEmpty() shouldContain "read nothing"
    }

    @Test
    fun `firstOf answers the first branch that does not wait`() {
        val east = Shared(0)
        val west = Shared(5)

        val from = transact {
            firstOf(
                { if (east.get() == 0) waitForChange() else "east" },
                { if (west.get() == 0) waitForChange() else "west" },
            )
        }

        from shouldBe "west"
    }

    @Test
    fun `firstOf drops the writes of a branch that waited`() {
        val east = Shared(0)
        val west = Shared(5)
        val taken = Shared("nothing")

        transact {
            firstOf(
                {
                    taken.set("east")
                    if (east.get() == 0) waitForChange()
                },
                { west.update { it - 1 } },
            )
        }

        taken.peek() shouldBe "nothing"
        west.peek() shouldBe 4
    }

    @Test
    fun `when every branch waits, a commit to any of them wakes the step`() {
        val east = Shared(0)
        val west = Shared(0)

        val buyer = Thread.ofVirtual().start {
            transact {
                firstOf(
                    { if (east.get() == 0) waitForChange() else east.update { it - 1 } },
                    { if (west.get() == 0) waitForChange() else west.update { it - 1 } },
                )
            }
        }
        buyer.awaitParkedOn(west)
        transact { west.set(1) }
        buyer.join()

        west.peek() shouldBe 0
        east.peek() shouldBe 0
    }
}
