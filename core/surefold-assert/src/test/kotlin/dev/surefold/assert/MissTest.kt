package dev.surefold.assert

import org.junit.jupiter.api.Test

@OptIn(FailureSeam::class)
class MissTest {

    @Test
    fun `a check that holds answers null, and one that does not answers what it found`() {
        1.notEqual(1).shouldBeNull()
        1.notEqual(2) shouldBe Miss(expected = 2, actual = 1)
        3.notAtMost(2) shouldBe Miss(expected = null, actual = 3, expectedText = "at most 2")
        "rex".notInstanceOf<String>().shouldBeNull()
    }

    @Test
    fun `the string, collection and map checks answer the same way`() {
        "a lost ask".missing("lost").shouldBeNull()
        (null as String?).missing("lost") shouldBe
            Miss(expected = null, actual = null, expectedText = "a string containing \"lost\"")
        listOf(1, 2).missingInAnyOrder(listOf(2, 1)).shouldBeNull()
        listOf(1, 2).notExactly(listOf(2, 1)) shouldBe Miss(expected = listOf(2, 1), actual = listOf(1, 2))
        mapOf("a" to 1).missingKey("a").shouldBeNull()
        emptyMap<String, Int>().empty() shouldBe
            Miss(expected = null, actual = emptyMap<String, Int>(), "at least one entry")
    }
}
