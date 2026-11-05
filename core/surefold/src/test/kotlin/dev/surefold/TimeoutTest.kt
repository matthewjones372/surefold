package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldThrow
import dev.surefold.assert.withClue
import dev.surefold.fail.attempt
import dev.surefold.fail.left
import dev.surefold.fail.right
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

// Long enough that a timeout waiting its sleeper out instead of interrupting it would blow the bound below.
private val NEVER_ARRIVES = NEVER_FINISHES_MILLIS.milliseconds

class TimeoutTest {

    @Test
    fun `a block that does not answer in time is interrupted, and timeout throws`() {
        val slow = Sleeper()

        shouldThrow<TimeoutException> { timeout(50.milliseconds) { slow.body() } }

        slow.wasInterrupted() shouldBe true
        withClue("a scope that returns with the loser still running is a leak") {
            slow.isAlive() shouldBe false
        }
    }

    @Test
    fun `timeoutOrNull gives null for the same block`() {
        val slow = Sleeper()

        timeoutOrNull(50.milliseconds) { slow.body() } shouldBe null

        slow.wasInterrupted() shouldBe true
        slow.isAlive() shouldBe false
    }

    @Test
    fun `a block that answers in time returns its value, and the sleeper is interrupted`() {
        val startedAt = System.nanoTime()

        timeout(NEVER_ARRIVES) { 42 } shouldBe 42
        timeoutOrNull(NEVER_ARRIVES) { "answered" } shouldBe "answered"

        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        withClue("the winner interrupts the sleeper, so neither call can have waited it out") {
            (elapsedMillis < PROMPT_MILLIS) shouldBe true
        }
    }

    @Test
    fun `a raise inside the block still raises`() {
        attempt<Bad, Int> {
            timeout(NEVER_ARRIVES) { fail(Bad("declared")) }
        } shouldBe Bad("declared").left()

        attempt<Bad, Int?> {
            timeoutOrNull(NEVER_ARRIVES) { fail(Bad("declared")) }
        } shouldBe Bad("declared").left()

        attempt<Bad, Int> {
            timeout(NEVER_ARRIVES) { 42 }
        } shouldBe 42.right()

        attempt<Bad, Int?> {
            timeoutOrNull(NEVER_ARRIVES) { 42 }
        } shouldBe 42.right()
    }

    @Test
    fun `a throw inside the block rethrows rather than timing out`() {
        val boom = Boom()

        shouldThrow<Boom> { timeout(1.seconds) { throw boom } }
        shouldThrow<Boom> { timeoutOrNull(1.seconds) { throw boom } }
    }
}
