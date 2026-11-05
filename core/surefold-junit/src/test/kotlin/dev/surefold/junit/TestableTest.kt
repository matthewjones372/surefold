package dev.surefold.junit

import dev.surefold.assert.shouldBe
import dev.surefold.junit.samples.MixedSuite
import org.junit.jupiter.api.Test
import org.junit.platform.commons.annotation.Testable

/**
 * A suite object is `@Testable` through its superclasses, which is where IntelliJ looks for it before giving a class a
 * run icon (spec 0251). The annotation is not `@Inherited`, so the walk up the hierarchy is the test's to make.
 */
class TestableTest {

    private fun testableInHierarchy(type: Class<*>): Boolean =
        generateSequence(type) { it.superclass }.any { it.isAnnotationPresent(Testable::class.java) }

    @Test
    fun `a suite that shares nothing is testable through its superclasses`() {
        testableInHierarchy(MixedSuite::class.java) shouldBe true
    }

    @Test
    fun `a suite that shares a value is testable through its superclasses`() {
        testableInHierarchy(PassingSuite::class.java) shouldBe true
    }
}
