package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeInstanceOf
import dev.surefold.assert.shouldBeLeft
import dev.surefold.assert.shouldBeRight
import dev.surefold.assert.shouldBeSameInstanceAs
import dev.surefold.assert.shouldContainExactly
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.shouldThrow
import dev.surefold.assert.withClue
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.left
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Test

private class Held(val name: String)

class ResourceTest {

    // Independent release defects and same-signal observations require five explicit throws.
    @Suppress("ThrowsCount")
    @Test
    fun `nested cleanup retains the original fail signal through every finally`() {
        val error = Any()
        val innerCleanup = IllegalStateException("inner cleanup")
        val outerCleanup = IllegalStateException("outer cleanup")
        val original = Shared<Throwable?>(null)
        val afterInner = Shared(emptyList<Throwable>())
        val events = Shared(emptyList<Int>())
        val exits = Shared(emptyList<ExitCase>())
        val answer =
            attempt<Any, Unit> {
                val outer =
                    shouldThrow<Throwable> {
                        try {
                            resourceScope {
                                onRelease { exit ->
                                    events.update { it + 1 }
                                    exits.update { it + exit }
                                    throw outerCleanup
                                }
                                val inner =
                                    shouldThrow<Throwable> {
                                        try {
                                            resourceScope {
                                                onRelease { exit ->
                                                    events.update { it + 2 }
                                                    exits.update { it + exit }
                                                    throw innerCleanup
                                                }
                                                val signal = shouldThrow<Throwable> { fail(error) }
                                                original.update { signal }
                                                signal.stackTrace.toList() shouldBe emptyList()
                                                signal.suppressed.toList() shouldBe emptyList()
                                                throw signal
                                            }
                                        } finally {
                                            events.update { it + 3 }
                                        }
                                    }
                                inner shouldBeSameInstanceAs original.peek()
                                inner.stackTrace.toList() shouldBe emptyList()
                                afterInner.update { inner.suppressed.toList() }
                                throw inner
                            }
                        } finally {
                            events.update { it + 4 }
                        }
                    }
                outer shouldBeSameInstanceAs original.peek()
                outer.stackTrace.toList() shouldBe emptyList()
                outer.suppressed.size shouldBe 2
                outer.suppressed[0] shouldBeSameInstanceAs innerCleanup
                outer.suppressed[1] shouldBeSameInstanceAs outerCleanup
                afterInner.peek().size shouldBe 1
                afterInner.peek()[0] shouldBeSameInstanceAs innerCleanup
                throw outer
            }
        (answer as Either.Left).value shouldBeSameInstanceAs error
        events.peek() shouldBe listOf(2, 3, 1, 4)
        exits.peek() shouldBe listOf(ExitCase.Completed, ExitCase.Completed)
    }

    @Test
    fun `ordinary interrupt and cancellation primaries retain both cleanup failures`() {
        val ordinary = IllegalStateException("ordinary")
        val interrupt = InterruptedException("interrupt")
        val cancellation = CancellationException("cancellation")
        listOf(
                ordinary to ExitCase.Failure(ordinary),
                interrupt to ExitCase.Cancelled(interrupt),
                cancellation to ExitCase.Completed,
            )
            .forEach { (primary, expectedExit) ->
                val firstCleanup = IllegalStateException("first cleanup")
                val secondCleanup = IllegalStateException("second cleanup")
                val released = Shared(emptyList<Pair<Int, ExitCase>>())
                val thrown =
                    shouldThrow<Throwable> {
                        attempt<Any, Unit> {
                            resourceScope {
                                onRelease { exit ->
                                    released.update { it + (1 to exit) }
                                    throw firstCleanup
                                }
                                onRelease { exit ->
                                    released.update { it + (2 to exit) }
                                    throw secondCleanup
                                }
                                throw primary
                            }
                        }
                    }
                thrown shouldBeSameInstanceAs primary
                thrown.suppressed.size shouldBe 2
                thrown.suppressed[0] shouldBeSameInstanceAs secondCleanup
                thrown.suppressed[1] shouldBeSameInstanceAs firstCleanup
                released.peek() shouldBe listOf(2 to expectedExit, 1 to expectedExit)
            }
    }

    @Test
    fun `releases run in reverse acquisition order`() {
        val released = mutableListOf<String>()

        resourceScope {
            install({ Held("outer") }) { held, _ -> released += held.name }
            install({ Held("inner") }) { held, _ -> released += held.name }
            "used"
        } shouldBe "used"

        withClue("a resource is released before whatever it was built from") {
            released shouldContainExactly listOf("inner", "outer")
        }
    }

    @Test
    fun `a block that returns releases with Completed`() {
        val exit = AtomicReference<ExitCase?>(null)

        resourceScope {
            install({ Held("held") }) { _, case -> exit.set(case) }
            42
        } shouldBe 42

        exit.get() shouldBe ExitCase.Completed
    }

    @Test
    fun `a block that throws releases with Failure, and the throw is not swallowed`() {
        val boom = Boom()
        val exit = AtomicReference<ExitCase?>(null)

        val thrown =
            shouldThrow<Boom> {
                resourceScope {
                    install({ Held("held") }) { _, case -> exit.set(case) }
                    throw boom
                }
            }

        thrown shouldBeSameInstanceAs boom
        exit.get() shouldBe ExitCase.Failure(boom)
    }

    @Test
    fun `a block that is interrupted releases with Cancelled`() {
        val exit = AtomicReference<ExitCase?>(null)
        val started = CountDownLatch(1)

        val outcome =
            attempt<Bad, String> {
                scope {
                    async {
                        // Caught here, or the scope would close with the interrupt it sent rather than a value.
                        try {
                            resourceScope {
                                install({ Held("held") }) { _, case -> exit.set(case) }
                                started.countDown()
                                Thread.sleep(NEVER_FINISHES_MILLIS)
                                "slept"
                            }
                        } catch (stop: InterruptedException) {
                            "interrupted"
                        }
                    }
                    started.await()
                    "returned"
                }
            }

        outcome shouldBeRight "returned"
        val cancelled = exit.get().shouldBeInstanceOf<ExitCase.Cancelled>()
        withClue("interrupt is the only cancellation the JDK has, so Cancelled carries one") {
            cancelled.interrupt.shouldNotBeNull()
        }
    }

    @Test
    fun `a raise releases with Completed, and the scope's result is the Left`() {
        val exit = AtomicReference<ExitCase?>(null)
        val released = mutableListOf<String>()

        val raised =
            attempt<Bad, Int> {
                resourceScope {
                    install({ Held("outer") }) { held, case ->
                        released += held.name
                        exit.set(case)
                    }
                    install({ Held("inner") }) { held, _ -> released += held.name }
                    fail(Bad("declared"))
                }
            }

        raised shouldBe Bad("declared").left()
        released shouldContainExactly listOf("inner", "outer")
        withClue("a declared failure leaves the enclosing either with a value, not with a throw") {
            exit.get() shouldBe ExitCase.Completed
        }
    }

    @Test
    fun `a release that throws is the failure when nothing else was, and the rest still run`() {
        val boom = Boom()
        val released = mutableListOf<String>()

        val thrown =
            shouldThrow<Boom> {
                resourceScope {
                    install({ Held("outer") }) { held, _ -> released += held.name }
                    install({ Held("inner") }) { _, _ -> throw boom }
                    "used"
                }
            }

        thrown shouldBeSameInstanceAs boom
        withClue("a release that throws does not strand the resources acquired before it") {
            released shouldContainExactly listOf("outer")
        }
    }

    @Test
    fun `a release that throws after a failure is suppressed onto it`() {
        val fromBlock = Boom()
        val fromRelease = Boom()

        val thrown =
            shouldThrow<Boom> {
                resourceScope {
                    install({ Held("held") }) { _, _ -> throw fromRelease }
                    throw fromBlock
                }
            }

        thrown shouldBeSameInstanceAs fromBlock
        thrown.suppressed.toList() shouldContainExactly listOf(fromRelease)
    }

    @Test
    fun `a Resource composed from two installs is released when it is used`() {
        val released = mutableListOf<String>()

        val pair: Resource<Pair<Held, Held>> = resource {
            val outer = install({ Held("outer") }) { held, _ -> released += held.name }
            val inner = install({ Held("inner") }) { held, _ -> released += held.name }
            outer to inner
        }

        pair.use { (outer, inner) -> outer.name + inner.name } shouldBe "outerinner"
        released shouldContainExactly listOf("inner", "outer")
    }

    @Test
    fun `a Resource binds into a resourceScope, and its releases run with the scope's`() {
        val released = mutableListOf<String>()

        val held: Resource<Held> = resource {
            install({ Held("bound") }) { acquired, _ -> released += acquired.name }
        }

        resourceScope {
            val bound = held.bind()
            install({ Held("beside") }) { acquired, _ -> released += acquired.name }
            bound.name
        } shouldBe "bound"

        withClue("a bound resource's release joins this scope's, in acquisition order") {
            released shouldContainExactly listOf("beside", "bound")
        }
    }

    @Test
    fun `onRelease runs an action that holds no resource of its own`() {
        val exits = mutableListOf<ExitCase>()

        resourceScope {
            onRelease { exits += it }
            onRelease { exits += it }
            "used"
        } shouldBe "used"

        exits shouldContainExactly listOf(ExitCase.Completed, ExitCase.Completed)
    }

    @Test
    fun `a fail in a scope releases with Completed, as a raise does`() {
        val exit = AtomicReference<ExitCase?>(null)

        val failed =
            attempt<Bad, Int> {
                scope {
                    resourceScope {
                        install({ Held("held") }) { _, case -> exit.set(case) }
                        fail(Bad("declared"))
                    }
                }
            }

        failed shouldBeLeft Bad("declared")
        exit.get() shouldBe ExitCase.Completed
    }
}
