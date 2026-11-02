package dev.surefold.junit

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContainExactlyInAnyOrder
import dev.surefold.junit.samples.CluedSuite
import dev.surefold.junit.testkit.failures
import dev.surefold.junit.testkit.shouldHaveSucceeded
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit
import org.opentest4j.MultipleFailuresError

/** withClue puts its clue above each failure in its block, and shouldBeInstanceOf narrows before it checks. */
class CluesTest {

    private val events by lazy {
        EngineTestKit.engine("surefold").selectors(selectClass(CluedSuite::class.java)).execute().testEvents()
    }

    private val failures: Map<String, Throwable> by lazy { events.failures() }

    private fun lines(test: String) = failures.getValue(test).message.orEmpty().lines()

    @Test
    fun `a narrowed check passes, and every miss fails`() {
        events shouldHaveSucceeded
            listOf("passes a narrowed check", "passes under a lazy clue")
        failures.keys shouldContainExactlyInAnyOrder listOf(
            "fails under a clue",
            "fails one of two under a clue",
            "fails a narrowing",
            "fails a narrowed check",
            "fails under a lazy clue",
        )
    }

    @Test
    fun `a lazy clue is built only when its block fails`() {
        lines("fails under a lazy clue").take(3) shouldBe listOf(
            "fails under a lazy clue: 1 of 1 expectations failed",
            "built from two",
            "expected: 3",
        )
        CluedSuite.lazyClueBuilt.get() shouldBe 0
    }

    @Test
    fun `nested clues come first, outermost first`() {
        lines("fails under a clue").take(4) shouldBe listOf(
            "fails under a clue: 1 of 1 expectations failed",
            "the outer clue",
            "the inner clue",
            "expected: 3",
        )
    }

    @Test
    fun `a clue marks only the failures in its block, and the passing check still counts`() {
        val failed = failures.getValue("fails one of two under a clue")

        failed.message.orEmpty().lines().take(3) shouldBe listOf(
            "fails one of two under a clue: 1 of 2 expectations failed",
            "only once",
            "expected: 3",
        )
        (failed is MultipleFailuresError) shouldBe false
    }

    @Test
    fun `a narrowing that misses names the type wanted and the value found, and does not run its lambda`() {
        lines("fails a narrowing").take(3) shouldBe listOf(
            "fails a narrowing: 1 of 1 expectations failed",
            "expected: an instance of kotlin.Int",
            "but was:  two of kotlin.String",
        )
        CluedSuite.thenRan.get() shouldBe 0
    }

    @Test
    fun `a narrowed check that fails counts with the narrowing`() {
        lines("fails a narrowed check").take(2) shouldBe listOf(
            "fails a narrowed check: 1 of 2 expectations failed",
            "expected: three",
        )
    }
}
