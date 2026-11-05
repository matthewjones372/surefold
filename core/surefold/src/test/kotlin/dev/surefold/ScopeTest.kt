package dev.surefold

import dev.surefold.Start.Lazy
import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeLeft
import dev.surefold.assert.shouldBeRight
import dev.surefold.assert.shouldBeSameInstanceAs
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.shouldNotBeSameInstanceAs
import dev.surefold.assert.shouldThrow
import dev.surefold.assert.withClue
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.ensure
import dev.surefold.fail.left
import dev.surefold.fail.right
import java.util.concurrent.CountDownLatch
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test

class ScopeTest {

    private val twentyOne: Either<Bad, Int> = 21.right()
    private val broken: Either<Bad, Int> = Bad("bound").left()

    @Test
    fun `a value forked with async comes back from await, and the block's value is the Right`() {
        val sum =
            attempt<Bad, Int> {
                scope {
                    val first = async { 20 }
                    val second = async { 22 }
                    first.await() + second.await()
                }
            }

        sum shouldBeRight 42
    }

    @Test
    fun `a raise inside a fork surfaces at await as the block's Left`() {
        val afterTheAwait = AtomicBoolean(false)

        val result =
            attempt<Bad, Int> {
                scope {
                    val forked = async<Int> { fail(Bad("forked")) }
                    val value = forked.await()
                    afterTheAwait.set(true)
                    value
                }
            }

        result shouldBeLeft Bad("forked")
        withClue("await() must leave the block by raising, not return to it") {
            afterTheAwait.get() shouldBe false
        }
    }

    @Test
    fun `a throw inside a fork rethrows at await as the same instance`() {
        val boom = Boom()

        val thrown =
            shouldThrow<Boom> {
                attempt<Bad, Int> { scope { async<Int> { throw boom }.await() } }
            }

        thrown shouldBeSameInstanceAs boom
    }

    @Test
    fun `a fork runs on a virtual thread of its own, not on the caller's`() {
        val caller = Thread.currentThread()

        val forked =
            attempt<Bad, Thread> { scope { async { Thread.currentThread() }.await() } }.getOrNull().shouldNotBeNull()

        withClue("a fork is started with Thread.ofVirtual(), so isVirtual holds in its body") {
            forked.isVirtual shouldBe true
        }
        forked shouldNotBeSameInstanceAs caller
    }

    @Test
    fun `a fork left running when the block returns is interrupted and joined before scope returns`() {
        val sleeper = Sleeper()

        val startedAt = System.nanoTime()
        val result =
            attempt<Bad, String> {
                scope {
                    async { sleeper.body() }
                    sleeper.awaitStart()
                    "returned"
                }
            }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        result shouldBeRight "returned"
        sleeper.wasInterrupted() shouldBe true
        withClue("close interrupts, so scope cannot have waited out the fork's sleep") {
            (elapsedMillis < PROMPT_MILLIS) shouldBe true
        }
        withClue("a scope that returns with a thread still running is a leak") {
            sleeper.isAlive() shouldBe false
        }
    }

    @Test
    fun `a cancelled fork is interrupted and dead before cancel returns`() {
        val sleeper = Sleeper()
        val interruptedAtCancel = AtomicBoolean(false)
        val aliveAtCancel = AtomicBoolean(true)

        val result =
            attempt<Bad, String> {
                scope {
                    val fork = async { sleeper.body() }
                    sleeper.awaitStart()
                    fork.cancel()
                    interruptedAtCancel.set(sleeper.wasInterrupted())
                    aliveAtCancel.set(sleeper.isAlive())
                    "returned"
                }
            }

        result shouldBeRight "returned"
        interruptedAtCancel.get() shouldBe true
        withClue("cancel returns only once the fork it stopped has ended") {
            aliveAtCancel.get() shouldBe false
        }
    }

    @Test
    fun `a fork cancelled and never awaited does not fail the block`() {
        val started = CountDownLatch(1)

        val result =
            attempt<Bad, String> {
                scope {
                    val fork = async {
                        started.countDown()
                        // Nothing catches it, so the interrupt is what this fork ends with.
                        Thread.sleep(NEVER_FINISHES_MILLIS)
                        "slept"
                    }
                    started.await()
                    fork.cancel()
                    "returned"
                }
            }

        result shouldBeRight "returned"
    }

    @Test
    fun `a fork cancelled after it raised does not fail the block`() {
        val result =
            attempt<Bad, String> {
                scope {
                    val fork = async<Int> { fail(Bad("forked")) }
                    fork.cancel()
                    "returned"
                }
            }

        result shouldBeRight "returned"
    }

    @Test
    fun `a sibling of a cancelled fork runs to completion`() {
        val sleeper = Sleeper()

        val result =
            attempt<Bad, Int> {
                scope {
                    val stopped = async { sleeper.body() }
                    val sibling = async { 42 }
                    sleeper.awaitStart()
                    stopped.cancel()
                    sibling.await()
                }
            }

        result shouldBeRight 42
        sleeper.wasInterrupted() shouldBe true
    }

    @Test
    fun `a lazy fork nobody awaits never runs, and the scope still returns`() {
        val ran = AtomicBoolean(false)

        val result =
            attempt<Bad, String> {
                scope {
                    async(start = Lazy) { ran.set(true) }
                    "returned"
                }
            }

        result shouldBeRight "returned"
        withClue("a scope that closes must not wait on a fork that never ran") {
            ran.get() shouldBe false
        }
    }

    @Test
    fun `a lazy fork runs when it is awaited`() {
        val result = attempt<Bad, Int> { scope { async(start = Lazy) { 42 }.await() } }

        result shouldBeRight 42
    }

    @Test
    fun `a lazy fork nobody awaits cannot fail the block, where the eager form would`() {
        val lazily =
            attempt<Bad, String> {
                scope {
                    async<Int>(start = Lazy) { fail(Bad("never asked for")) }
                    "returned"
                }
            }
        val eagerly =
            attempt<Bad, String> {
                scope {
                    async<Int> { fail(Bad("forked")) }
                    "returned"
                }
            }

        lazily shouldBeRight "returned"
        eagerly shouldBeLeft Bad("forked")
    }

    @Test
    fun `a lazy fork awaited twice runs once`() {
        val runs = AtomicInteger(0)

        val result =
            attempt<Bad, Int> {
                scope {
                    val counted = async(start = Lazy) { runs.incrementAndGet() }
                    counted.await() + counted.await()
                }
            }

        result shouldBeRight 2
        runs.get() shouldBe 1
    }

    @Test
    fun `a lazy fork cancelled before it was awaited never runs`() {
        val ran = AtomicBoolean(false)

        val result =
            attempt<Bad, String> {
                scope {
                    val fork = async(start = Lazy) { ran.set(true) }
                    fork.cancel()
                    "returned"
                }
            }

        result shouldBeRight "returned"
        ran.get() shouldBe false
    }

    @Test
    fun `a fork that raised and was never awaited becomes the block's Left`() {
        val result =
            attempt<Bad, String> {
                scope {
                    async { fail(Bad("orphan")) }
                    "returned anyway"
                }
            }

        result shouldBeLeft Bad("orphan")
    }

    @Test
    fun `a fork that threw and was never awaited rethrows when the scope closes`() {
        val boom = Boom()

        val thrown =
            shouldThrow<Boom> {
                attempt<Bad, String> {
                    scope {
                        async { throw boom }
                        "returned anyway"
                    }
                }
            }

        thrown shouldBeSameInstanceAs boom
    }

    @Test
    fun `scope answers its block's value, with every fork ended by the time it does`() {
        val forkEnded = AtomicBoolean(false)

        val sum: Int = scope {
            async { forkEnded.set(true) }
            async { 20 }.await() + 22
        }

        sum shouldBe 42
        forkEnded.get() shouldBe true
    }

    @Test
    fun `inside attempt, scope is the attempt's own, so a fork's fail is the attempt's Left`() {
        val result =
            attempt<Bad, Int> {
                scope {
                    // Only a Scope<Bad> compiles here: the top-level scope would be a Scope<Nothing>.
                    val typed: Scope<Bad> = this
                    typed.async<Int> { fail(Bad("forked")) }.await()
                }
            }

        result shouldBeLeft Bad("forked")
    }

    @Test
    fun `a fork that threw and was never awaited rethrows from scope`() {
        val boom = Boom()

        val thrown =
            shouldThrow<Boom> {
                scope {
                    async { throw boom }
                    "returned anyway"
                }
            }

        thrown shouldBeSameInstanceAs boom
    }

    @Test
    fun `when two unawaited forks fail, the first in start order is the scope's failure`() {
        val secondStarted = CountDownLatch(1)
        val bothAtTheRaise = CountDownLatch(2)

        val result =
            attempt<Bad, String> {
                scope {
                    async {
                        // The fork started first fails last, so finishing order would answer "second".
                        secondStarted.await()
                        bothAtTheRaise.countDown()
                        fail(Bad("first"))
                    }
                    async<Nothing> {
                        secondStarted.countDown()
                        bothAtTheRaise.countDown()
                        fail(Bad("second"))
                    }
                    bothAtTheRaise.await()
                    "returned anyway"
                }
            }

        withClue("start order, not finishing order, names the scope's failure") {
            result shouldBeLeft Bad("first")
        }
    }

    @Test
    fun `a fork forked inside a fork is joined when the outer fork's body ends`() {
        val inner = Sleeper()

        val outcome =
            attempt<Bad, Pair<String, Boolean>> {
                scope {
                    val outer = async {
                        async { inner.body() }
                        inner.awaitStart()
                        "outer done"
                    }
                    outer.await() to inner.isAlive()
                }
            }

        outcome shouldBeRight ("outer done" to false)
        inner.wasInterrupted() shouldBe true
    }

    @Test
    fun `a raise on the calling thread interrupts and joins a running fork before scope returns`() {
        val sleeper = Sleeper()

        val result =
            attempt<Bad, String> {
                scope {
                    async { sleeper.body() }
                    sleeper.awaitStart()
                    fail(Bad("caller"))
                }
            }

        result shouldBeLeft Bad("caller")
        sleeper.wasInterrupted() shouldBe true
        sleeper.isAlive() shouldBe false
    }

    @Test
    fun `bind and ensure work in the block`() {
        val bound =
            attempt<Bad, Int> {
                scope {
                    val value = twentyOne.bind()
                    ensure(value == 21) { Bad("not 21") }
                    value * 2
                }
            }

        bound shouldBeRight 42
        attempt<Bad, Int> { scope { broken.bind() } } shouldBeLeft Bad("bound")
        attempt<Bad, Int> {
            scope {
                ensure(false) { Bad("ensured") }
                1
            }
        } shouldBeLeft Bad("ensured")
    }

    @Test
    fun `bind and ensure work in a fork body`() {
        val bound =
            attempt<Bad, Int> {
                scope {
                    async {
                        val value = twentyOne.bind()
                        ensure(value == 21) { Bad("not 21") }
                        value * 2
                    }
                        .await()
                }
            }

        bound shouldBeRight 42
        attempt<Bad, Int> { scope { async { broken.bind() }.await() } } shouldBeLeft Bad("bound")
    }

    @Test
    fun `close hooks run last first, before any fork is interrupted, and can wait on a fork still running`() {
        val ran = mutableListOf<String>()
        val asks = SynchronousQueue<Int>()
        val answers = SynchronousQueue<Int>()

        val closed = scope {
            async {
                try {
                    while (true) answers.put(asks.take() * 2)
                } catch (stopping: InterruptedException) {
                    ran += "fork interrupted"
                }
            }
            onClose { ran += "first" }
            onClose {
                asks.put(21)
                ran += "second, answered ${answers.take()}"
            }
            "done"
        }

        closed shouldBe "done"
        ran shouldBe listOf("second, answered 42", "first", "fork interrupted")
    }

    @Test
    fun `a close hook registered while the scope closes runs too, after the one that registered it`() {
        val ran = mutableListOf<String>()

        scope {
            onClose { ran += "first" }
            onClose {
                ran += "second"
                onClose { ran += "registered while closing" }
            }
        }

        ran shouldBe listOf("second", "registered while closing", "first")
    }

    @Test
    fun `a close hook that throws an Error still interrupts and joins every fork, and the Error is thrown after`() {
        val ran = mutableListOf<String>()

        shouldThrow<AssertionError> {
            scope {
                async {
                    try {
                        CountDownLatch(1).await()
                    } catch (stopping: InterruptedException) {
                        ran += "fork interrupted"
                    }
                }
                onClose { throw AssertionError("the hook broke") }
                7
            }
        }

        ran shouldBe listOf("fork interrupted")
    }

    @Test
    fun `a close hook that throws is logged, and the hooks after it and the close still run`() {
        val ran = mutableListOf<String>()

        val closed = scope {
            onClose { ran += "earlier" }
            onClose { error("the hook broke") }
            7
        }

        closed shouldBe 7
        ran shouldBe listOf("earlier")
    }

    @Test
    fun `an interrupted close hook ends, the rest run, and the closing thread is interrupted afterwards`() {
        val ran = mutableListOf<String>()

        scope {
            onClose { ran += "earlier" }
            onClose { throw InterruptedException("stop waiting") }
        }

        Thread.interrupted() shouldBe true
        ran shouldBe listOf("earlier")
    }
}
