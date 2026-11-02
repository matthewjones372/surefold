package dev.surefold.test

import dev.surefold.TestClock
import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeSameInstanceAs
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldThrow
import dev.surefold.assert.withClue
import dev.surefold.clock
import dev.surefold.scope
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EventuallyTest {

    @Test
    fun `eventually answers the first try that does not throw`() {
        val tries = AtomicInteger(0)

        val answer = eventually(1.seconds, every = 1.milliseconds) {
            check(tries.incrementAndGet() >= 3) { "not yet" }
            "there"
        }

        answer shouldBe "there"
        tries.get() shouldBe 3
    }

    @Test
    fun `a failing eventually gives up on time, naming the last failure, the tries and the time taken`() {
        val moving = TestClock()
        val last = AssertionError("expected:<0L> but was:<3L>")

        val gaveUp = clock.locally(moving) {
            scope {
                val waiting = async { shouldThrow<GaveUp> { eventually(5.seconds, every = 1.seconds) { throw last } } }
                repeat(5) { moving.adjustWhenBlocked(1.seconds) }
                waiting.await()
            }
        }

        gaveUp.cause shouldBeSameInstanceAs last
        withClue("a try at each second from 0 to 5, and the one at 5 finds five seconds gone") {
            gaveUp.tries shouldBe 6L
            gaveUp.elapsed shouldBe 5.seconds
        }
        gaveUp.message!! shouldContain "6 tries"
        gaveUp.message!! shouldContain "5s"
        gaveUp.message!! shouldContain "expected:<0L> but was:<3L>"
    }

    @Test
    fun `an interrupt is not a failure to try again`() {
        shouldThrow<InterruptedException> {
            eventually(5.seconds) { throw InterruptedException("stop") }
        }
    }

    @Test
    fun `a verdict that has not held yet is tried again, and the first that holds is the answer`() {
        val tries = AtomicInteger(0)

        val answer = eventually(1.seconds, every = 1.milliseconds) { Checked(held = tries.incrementAndGet() >= 3) }

        answer shouldBe Checked(held = true)
        tries.get() shouldBe 3
    }

    @Test
    fun `a verdict that never holds is answered as given up, with the tries and the time taken, and nothing thrown`() {
        val moving = TestClock()

        val answer = clock.locally(moving) {
            scope {
                val waiting = async { eventually(5.seconds, every = 1.seconds) { Checked(held = false) } }
                repeat(5) { moving.adjustWhenBlocked(1.seconds) }
                waiting.await()
            }
        }

        answer shouldBe Checked(held = false, gaveUpAfter = 6L to 5.seconds)
    }
}

/** A verdict that holds as told, and remembers when it was given up on. */
private data class Checked(override val held: Boolean, val gaveUpAfter: Pair<Long, Duration>? = null) : Verdict {
    override fun gaveUp(tries: Long, elapsed: Duration): Checked = copy(gaveUpAfter = tries to elapsed)
}
