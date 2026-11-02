package dev.surefold.assert

import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ComparisonsTest {

    private fun failure(check: () -> Unit): List<String> =
        shouldThrow<AssertionFailedError> { check() }.message!!.lines().take(2)

    @Test
    fun `less than, and at most, by the bound`() {
        (1 shouldBeLessThan 2) shouldBe 1
        2 shouldBeLessThanOrEqual 2
        failure { 2 shouldBeLessThan 2 } shouldBe listOf("expected: less than 2", "but was:  2")
        failure { 3 shouldBeLessThanOrEqual 2 } shouldBe listOf("expected: at most 2", "but was:  3")
    }

    @Test
    fun `greater than, and at least, by the bound`() {
        (2L shouldBeGreaterThan 1L) shouldBe 2L
        1.5 shouldBeGreaterThanOrEqual 1.5
        failure { 1 shouldBeGreaterThan 1 } shouldBe listOf("expected: greater than 1", "but was:  1")
        failure { 0.5 shouldBeGreaterThanOrEqual 1.5 } shouldBe listOf("expected: at least 1.5", "but was:  0.5")
    }

    @Test
    fun `in a range, its ends included, for anything comparable`() {
        (5 shouldBeInRange 1..5) shouldBe 5
        300.milliseconds shouldBeLessThan 1.seconds
        failure { 6 shouldBeInRange 1..5 } shouldBe listOf("expected: in 1..5", "but was:  6")
    }
}
