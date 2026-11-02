package dev.surefold.fail

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeLeft
import dev.surefold.assert.shouldBeRight
import dev.surefold.assert.shouldThrow
import org.junit.jupiter.api.Test

private data class Invalid(val why: String)

class AccumulatingTest {

    @Test
    fun `three failed checks answer with all three, in the order they ran`() {
        attempt<NonEmptyList<Invalid>, String> {
            accumulate {
                ensureOrAccumulate(false) { Invalid("a") }
                ensureOrAccumulate(true) { Invalid("not this") }
                ensureOrAccumulate(false) { Invalid("b") }
                ensureOrAccumulate(false) { Invalid("c") }
            }
            "unreached"
        } shouldBeLeft nonEmptyListOf(Invalid("a"), Invalid("b"), Invalid("c"))
    }

    @Test
    fun `a block that records nothing answers its value`() {
        attempt<NonEmptyList<Invalid>, Int> {
            accumulate {
                ensureOrAccumulate(true) { Invalid("a") }
                42
            }
        } shouldBeRight 42
    }

    @Test
    fun `bindOrAccumulate answers the value, or records the error and answers null`() {
        var seen: Int? = -1
        attempt<NonEmptyList<Invalid>, Int?> {
            accumulate {
                val good = 3.right().bindOrAccumulate()
                seen = Invalid("bad").left().bindOrAccumulate<Invalid, Int>()
                good
            }
        } shouldBeLeft nonEmptyListOf(Invalid("bad"))
        seen shouldBe null
    }

    @Test
    fun `a plain fail, as ensure makes, records its error beside the earlier ones and ends the block`() {
        var after = false
        attempt<NonEmptyList<Invalid>, Unit> {
            accumulate {
                ensureOrAccumulate(false) { Invalid("first") }
                ensure(after) { Invalid("stop") }
                after = true
            }
        } shouldBeLeft nonEmptyListOf(Invalid("first"), Invalid("stop"))
        after shouldBe false
    }

    @Test
    fun `bindAll keeps every error of a nested list beside the recorded ones`() {
        attempt<NonEmptyList<Invalid>, Unit> {
            accumulate {
                ensureOrAccumulate(false) { Invalid("first") }
                nonEmptyListOf(Invalid("x"), Invalid("y")).left().bindAll<Invalid, Unit>()
            }
        } shouldBeLeft nonEmptyListOf(Invalid("first"), Invalid("x"), Invalid("y"))
    }

    @Test
    fun `an Accumulating used after its block has returned is refused`() {
        var leaked: Accumulating<Invalid>? = null
        attempt<NonEmptyList<Invalid>, Unit> { accumulate { leaked = this } }

        val refused = shouldThrow<IllegalStateException> {
            with(checkNotNull(leaked)) { ensureOrAccumulate(false) { Invalid("late") } }
        }
        ("after its block had returned" in refused.message.orEmpty()) shouldBe true
    }
}
