package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldThrow
import dev.surefold.fail.Either
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test

private const val CALLERS = 20

// Enough rounds that a cancel landing as a block starts, before its flight is registered, is all but certain.
private const val ROUNDS = 500

/** One call per key while it is in flight, however many ask (spec 0247). */
class SingleFlightTest {

    @Test
    fun `twenty callers at once run the block once and all get its answer`() {
        val flights = SingleFlight<String, String, Int>()
        val runs = AtomicInteger()
        val release = Promise<Nothing, Unit>()
        val answers =
            parZip(
                {
                    parMap(1..CALLERS) {
                        flights.run("k") {
                            runs.incrementAndGet()
                            release.awaitValue()
                            Either.Right(7)
                        }
                    }
                },
                {
                    // Every caller is parked on the one flight, so none can arrive after it ends and start another.
                    while (flights.waiting("k") < CALLERS) Thread.onSpinWait()
                    release.succeed(Unit)
                },
            ) { all, _ ->
                all
            }
        answers.toSet() shouldBe setOf(Either.Right(7))
        runs.get() shouldBe 1
    }

    @Test
    fun `cancelAll stops a block however soon after it started`() {
        repeat(ROUNDS) {
            val flights = SingleFlight<String, Nothing, Int>()
            val started = Promise<Nothing, Unit>()
            parZip(
                {
                    shouldThrow<InterruptedException> {
                        flights.run("k") {
                            started.succeed(Unit)
                            Promise<Nothing, Unit>().awaitValue()
                            Either.Right(1)
                        }
                    }
                },
                {
                    started.awaitValue()
                    flights.cancelAll()
                },
            ) { _, _ ->
            }
        }
    }

    @Test
    fun `a waiter that stops waiting does not stop the block for the others`() {
        val flights = SingleFlight<String, String, Int>()
        val release = Promise<Nothing, Unit>()
        val first =
            Thread.ofVirtual().start {
                try {
                    flights.run("k") {
                        release.awaitValue()
                        Either.Right(1)
                    }
                } catch (_: InterruptedException) {
                    // This caller gave up; the block is the others' still.
                }
            }
        while (flights.inFlight() == 0) Thread.onSpinWait()
        first.interrupt()
        first.join()
        val second = Promise<Nothing, Either<String, Int>>()
        val other = Thread.ofVirtual().start { second.succeed(flights.run("k") { Either.Right(2) }) }
        while (flights.waiting("k") < 1) Thread.onSpinWait()
        release.succeed(Unit)
        other.join()
        second.awaitValue() shouldBe Either.Right(1)
    }

    @Test
    fun `a throw reaches every waiter, and the next call runs again`() {
        val flights = SingleFlight<String, String, Int>()
        shouldThrow<IllegalStateException> { flights.run("k") { error("source down") } }
        flights.inFlight() shouldBe 0
        flights.run("k") { Either.Right(3) } shouldBe Either.Right(3)
    }

    @Test
    fun `different keys run apart`() {
        val flights = SingleFlight<String, String, String>()
        parMap(listOf("a", "b")) { key -> flights.run(key) { Either.Right(key) } } shouldBe
            listOf(Either.Right("a"), Either.Right("b"))
    }
}
