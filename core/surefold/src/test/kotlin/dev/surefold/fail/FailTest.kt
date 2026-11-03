package dev.surefold.fail

import dev.surefold.Shared
import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeSameInstanceAs
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.shouldThrow
import dev.surefold.resourceScope
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

class FailTest {

    private data class NotFound(val id: Int)

    private data class Invalid(val reason: String)

    context(_: Fail<NotFound>)
    private fun find(id: Int): String = if (id == 1) "one" else fail(NotFound(id))

    context(scope: Fail<String>)
    private fun scope(): Fail<String> = scope

    @Test
    fun `a foreign attempt rethrows the owning fail signal with cleanup intact`() {
        val error = Any()
        val cleanup = IllegalStateException("cleanup")
        val original = Shared<Throwable?>(null)
        val foreignTail = Shared(false)
        val answer = attempt<Any, Unit> {
            val owner = this
            val crossed = shouldThrow<Throwable> {
                attempt<String, Unit> {
                    resourceScope<Unit> {
                        onRelease { throw cleanup }
                        val signal = shouldThrow<Throwable> { owner.fail(error) }
                        original.update { signal }
                        signal.stackTrace.toList() shouldBe emptyList()
                        throw signal
                    }
                    foreignTail.update { true }
                }
            }
            crossed shouldBeSameInstanceAs original.peek()
            crossed.stackTrace.toList() shouldBe emptyList()
            crossed.suppressed.size shouldBe 1
            crossed.suppressed[0] shouldBeSameInstanceAs cleanup
            throw crossed
        }
        (answer as Either.Left).value shouldBeSameInstanceAs error
        foreignTail.peek() shouldBe false
    }

    @Test
    fun `cleanup belongs to the fail signal and leaves a throwable error unchanged`() {
        val error = IllegalArgumentException("declared")
        val prior = IllegalStateException("prior")
        val cleanup = IllegalStateException("cleanup")
        error.addSuppressed(prior)
        error.suppressed.size shouldBe 1
        error.suppressed[0] shouldBeSameInstanceAs prior
        val answer = attempt<Throwable, Unit> {
            val signal = shouldThrow<Throwable> {
                resourceScope {
                    onRelease { throw cleanup }
                    fail(error)
                }
            }
            signal.suppressed.size shouldBe 1
            signal.suppressed[0] shouldBeSameInstanceAs cleanup
            error.suppressed.size shouldBe 1
            error.suppressed[0] shouldBeSameInstanceAs prior
            throw signal
        }
        (answer as Either.Left).value shouldBeSameInstanceAs error
        error.suppressed.size shouldBe 1
        error.suppressed[0] shouldBeSameInstanceAs prior
    }

    @Test
    fun `a block that returns answers on the right`() {
        attempt<NotFound, String> { find(1) } shouldBe "one".right()
    }

    @Test
    fun `a block that fails answers on the left with its error`() {
        attempt<NotFound, String> { find(2) } shouldBe NotFound(2).left()
    }

    @Test
    fun `bind returns a right and fails with a left`() {
        attempt<String, Int> { 1.right().bind() + 1 } shouldBe 2.right()
        attempt<String, Int> { "no".left().bind() } shouldBe "no".left()
    }

    @Test
    fun `ensure fails when its condition is false and smart-casts after it`() {
        val value: Any = "text"
        attempt<String, Int> {
            ensure(value is String) { "not a string" }
            value.length
        } shouldBe 4.right()
        attempt<String, Int> {
            ensure(false) { "false" }
            0
        } shouldBe "false".left()
    }

    @Test
    fun `ensureNotNull returns the value or fails`() {
        val present: String? = "x"
        val absent: String? = null
        attempt<String, String> { ensureNotNull(present) { "absent" } } shouldBe "x".right()
        attempt<String, String> { ensureNotNull(absent) { "absent" } } shouldBe "absent".left()
    }

    @Test
    fun `recover answers a failure with a value`() {
        recover<NotFound, String>({ find(3) }) { "missing ${it.id}" } shouldBe "missing 3"
    }

    @Test
    fun `two error types sit side by side and each failure reaches its own attempt`() {
        val outer = attempt<NotFound, Either<Invalid, String>> {
            attempt<Invalid, String> { find(4) }
        }
        outer shouldBe NotFound(4).left()

        val inner = attempt<NotFound, Either<Invalid, String>> {
            attempt<Invalid, String> { fail(Invalid("bad")) }
        }
        inner shouldBe Invalid("bad").left().right()
    }

    // The claim is about exactly this broad catch.
    @Suppress("TooGenericExceptionCaught")
    @Test
    fun `catching Exception in the block does not swallow a failure`() {
        val answered = attempt<NotFound, String> {
            try {
                find(5)
            } catch (e: Exception) {
                "swallowed"
            }
        }
        answered shouldBe NotFound(5).left()
    }

    @Test
    fun `a scope that escaped its attempt names where it was opened when it fails`() {
        var leaked: Fail<String>? = null
        attempt<String, Unit> { leaked = scope() }

        val error = shouldThrow<IllegalStateException> { with(leaked!!) { fail("late") } }
        error.message shouldContain "after its attempt had returned"
        error.message shouldContain FailTest::class.java.name
    }

    @Test
    fun `a fail from a thread the scope did not fork names where it was opened, and leaves the attempt open`() {
        var thrown: IllegalStateException? = null

        val answered = attempt<String, String> {
            val scope = scope()
            val stray = Thread.ofVirtual().start {
                try {
                    with(scope) { fail("stray") }
                } catch (refused: IllegalStateException) {
                    thrown = refused
                }
            }
            stray.join()
            "carried on"
        }

        answered shouldBe "carried on".right()
        val error = thrown.shouldNotBeNull()
        error.message shouldContain "a thread it did not fork"
        error.message shouldContain FailTest::class.java.name
    }

    @Test
    fun `a throw that is not a failure passes through attempt`() {
        shouldThrow<IllegalArgumentException> { attempt<String, Unit> { throw IllegalArgumentException("bug") } }
    }

    @Test
    fun `nonFatalOrThrow returns an ordinary throwable and rethrows the rest`() {
        val ordinary = IllegalStateException("ordinary")
        ordinary.nonFatalOrThrow() shouldBe ordinary
        shouldThrow<InterruptedException> { InterruptedException().nonFatalOrThrow() }
        shouldThrow<CancellationException> { CancellationException().nonFatalOrThrow() }
        shouldThrow<OutOfMemoryError> { OutOfMemoryError().nonFatalOrThrow() }
    }

    // The claim is about exactly this broad catch.
    @Suppress("TooGenericExceptionCaught")
    @Test
    fun `nonFatalOrThrow puts a failure back on its way out`() {
        val answered = attempt<String, String> {
            try {
                fail("declared")
            } catch (t: Throwable) {
                t.nonFatalOrThrow()
                "handled"
            }
        }
        answered shouldBe "declared".left()
    }
}
