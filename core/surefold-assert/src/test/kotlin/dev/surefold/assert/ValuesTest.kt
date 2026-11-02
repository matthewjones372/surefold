package dev.surefold.assert

import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError

class ValuesTest {

    private fun failure(check: () -> Unit): List<String> =
        shouldThrow<AssertionFailedError> { check() }.message!!.lines().take(2)

    private class Box(val name: String) {
        override fun equals(other: Any?) = other is Box && other.name == name

        override fun hashCode() = name.hashCode()

        override fun toString() = "Box($name)"
    }

    @Test
    fun `shouldNotBe passes a different value and names the one it refused`() {
        (1 shouldNotBe 2) shouldBe 1
        failure { 1 shouldNotBe 1 } shouldBe listOf("expected: anything but 1", "but was:  1")
    }

    @Test
    fun `shouldBeNull passes null and shows what was there instead`() {
        null.shouldBeNull()
        failure { "rex".shouldBeNull() } shouldBe listOf("expected: null", "but was:  rex")
    }

    @Test
    fun `shouldNotBeNull returns the value, and says it found null`() {
        val maybe: String? = "rex"
        maybe.shouldNotBeNull().length shouldBe 3
        failure { (null as String?).shouldNotBeNull() } shouldBe listOf("expected: a value, not null", "but was:  null")
    }

    @Test
    fun `shouldBeInstanceOf returns the value as the type, and names both types`() {
        val any: Any = "rex"
        any.shouldBeInstanceOf<String>().length shouldBe 3
        failure { 42.shouldBeInstanceOf<String>() } shouldBe
            listOf("expected: an instance of kotlin.String", "but was:  42 of kotlin.Int")
    }

    @Test
    fun `shouldBeSameInstanceAs tells an equal object from the same one`() {
        val one = Box("a")
        (one shouldBeSameInstanceAs one) shouldBe one

        val lines = failure { Box("a") shouldBeSameInstanceAs one }
        lines[0].startsWith("expected: Box(a) @") shouldBe true
        lines[1].startsWith("but was:  Box(a) @") shouldBe true
        (lines[0].substringAfter('@') != lines[1].substringAfter('@')) shouldBe true
    }

    @Test
    fun `shouldNotBeSameInstanceAs passes an equal copy and refuses the object itself`() {
        val one = Box("a")
        Box("a") shouldNotBeSameInstanceAs one

        val lines = failure { one shouldNotBeSameInstanceAs one }
        lines[0].startsWith("expected: anything but Box(a) @") shouldBe true
        lines[1].startsWith("but was:  Box(a) @") shouldBe true
    }
}
