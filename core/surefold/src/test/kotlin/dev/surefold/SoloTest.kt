package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldThrow
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

private const val SECTIONS = 1_000

private const val WRITERS = 8

// Far past any budget: a step still running by then has starved, and the test says so rather than hanging.
private const val STARVED = 10_000

/** Spec 0231: a step that keeps losing runs alone, and commits. */
@Timeout(1, unit = TimeUnit.MINUTES)
class SoloTest {

    // Each spin answers an interrupt, so the test timeout ends a wait that never comes rather than hanging the build.
    private fun spinUntil(done: () -> Boolean) {
        while (!done()) {
            if (Thread.interrupted()) throw InterruptedException()
            Thread.onSpinWait()
        }
    }

    @Test
    fun `a step reading a thousand values commits within eleven runs while eight threads write one of them`() {
        val sections = List(SECTIONS) { Shared(1) }
        val total = Shared(0)
        val writing = AtomicBoolean(true)
        val writes = AtomicInteger()
        // Platform threads, so the writers run beside the report rather than taking turns with it.
        val writers =
            List(WRITERS) {
                Thread.ofPlatform().start {
                    while (writing.get()) {
                        transact { sections[0].update { it + 1 } }
                        writes.incrementAndGet()
                    }
                }
            }
        spinUntil { writes.get() > WRITERS }
        val runs = AtomicInteger()

        try {
            transact("report") {
                check(runs.incrementAndGet() < STARVED) { "the report starved" }
                total.set(sections.sumOf { it.get() })
            }
        } finally {
            writing.set(false)
            writers.forEach { it.join() }
        }

        (runs.get() <= 11) shouldBe true
        (total.peek() >= SECTIONS) shouldBe true
    }

    @Test
    fun `a solo run that waits gives the word up, so the commit it waits for can land`() {
        val free = Shared(0)
        val buyer =
            Thread.ofVirtual().start {
                transact(soloAfter = 0) { if (free.get() == 0) waitForChange() else free.update { it - 1 } }
            }
        spinUntil { free.waiters.isNotEmpty() }

        transact { free.set(1) }
        buyer.join()

        free.peek() shouldBe 0
    }

    @Test
    fun `a solo run that throws gives the word up`() {
        val free = Shared(10)

        shouldThrow<IllegalStateException> { transact(soloAfter = 0) { error("payment refused") } }
        transact { free.update { it - 1 } }

        free.peek() shouldBe 9
    }

    @Test
    fun `a read-only solo run gives the word up`() {
        val free = Shared(10)

        transact(soloAfter = 0) { free.get() } shouldBe 10
        transact { free.update { it - 1 } }

        free.peek() shouldBe 9
    }

    @Test
    fun `a budget below zero is refused`() {
        shouldThrow<IllegalArgumentException> { transact(soloAfter = -1) {} }
    }
}
