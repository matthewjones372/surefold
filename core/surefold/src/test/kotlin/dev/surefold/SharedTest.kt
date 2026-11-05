package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldThrow
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Test

private const val FORKS = 1_000

/** One value changed in place, outside any written-out transaction (spec 0247). */
class SharedTest {

    @Test
    fun `a thousand forks each adding one leave a thousand`() {
        val count = Shared(0, name = "count")
        attempt<Nothing, List<Unit>> { parMap(1..FORKS) { count.update { n -> n + 1 } } } as Either.Right
        count.peek() shouldBe FORKS
    }

    @Test
    fun `modify changes the value and answers beside it, as one step`() {
        val members = Shared(setOf("ann", "bob"))
        members.modify { it - "bob" to ("bob" in it) } shouldBe true
        members.modify { it - "bob" to ("bob" in it) } shouldBe false
        members.peek() shouldBe setOf("ann")
    }

    @Test
    fun `inside a step, update is part of it, so both land or neither does`() {
        val stalls = Shared(120)
        val circle = Shared(60)
        shouldThrow<IllegalStateException> {
            transact {
                stalls.update { it - 2 }
                circle.update { it - 1 }
                error("changed my mind")
            }
        }
        stalls.peek() shouldBe 120
        circle.peek() shouldBe 60
    }

    @Test
    fun `awaitUntil wakes on the change that makes it true, and not before`() {
        val ready = Shared(0)
        val woke = AtomicBoolean(false)
        val waiter =
            Thread.ofVirtual().start {
                ready.awaitUntil { it >= 3 } shouldBe 3
                woke.set(true)
            }
        repeat(2) {
            ready.update { n -> n + 1 }
            parked(waiter)
            woke.get() shouldBe false
        }
        ready.update { it + 1 }
        waiter.join()
        woke.get() shouldBe true
    }

    // Until the waiter has seen the latest change and parked again, so a wrong wake would already have happened.
    private fun parked(thread: Thread) {
        while (thread.state != Thread.State.WAITING) Thread.onSpinWait()
    }
}
