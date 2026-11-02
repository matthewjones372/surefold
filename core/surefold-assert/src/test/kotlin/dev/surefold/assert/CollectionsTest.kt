package dev.surefold.assert

import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError

class CollectionsTest {

    private fun failure(check: () -> Unit): List<String> =
        shouldThrow<AssertionFailedError> { check() }.message!!.lines().take(2)

    @Test
    fun `shouldBeEmpty passes no elements and shows the ones it found`() {
        emptyList<Int>().shouldBeEmpty()
        failure { listOf(1, 2).shouldBeEmpty() } shouldBe listOf("expected: no elements", "but was:  [1, 2]")
    }

    @Test
    fun `shouldNotBeEmpty passes an element and says it found none`() {
        listOf(1).shouldNotBeEmpty() shouldBe listOf(1)
        failure { emptySet<Int>().shouldNotBeEmpty() } shouldBe listOf("expected: at least one element", "but was:  []")
    }

    @Test
    fun `shouldHaveSize passes the right count and gives the wrong one with the elements`() {
        listOf("a", "b") shouldHaveSize 2
        failure { listOf("a", "b") shouldHaveSize 3 } shouldBe listOf("expected: 3 elements", "but was:  2 in [a, b]")
    }

    @Test
    fun `shouldContain passes an element that is there and names the one that is not`() {
        setOf(1, 2) shouldContain 2
        failure { setOf(1, 2) shouldContain 3 } shouldBe
            listOf("expected: a collection containing 3", "but was:  [1, 2]")
    }

    @Test
    fun `shouldNotContain passes an element that is not there and names the one that is`() {
        listOf(1, 2) shouldNotContain 3
        failure { listOf(1, 2) shouldNotContain 2 } shouldBe
            listOf("expected: a collection without 2", "but was:  [1, 2]")
    }

    @Test
    fun `shouldContainAll passes a subset in any order and names what is missing`() {
        listOf(1, 2, 3) shouldContainAll listOf(3, 1)
        listOf("a", "b", "c").shouldContainAll("c", "a")
        failure { listOf(1, 2) shouldContainAll listOf(2, 3, 4) } shouldBe
            listOf("expected: all of [2, 3, 4]", "but was:  [1, 2], missing [3, 4]")
        failure { listOf("a").shouldContainAll("a", "b") } shouldBe
            listOf("expected: all of [a, b]", "but was:  [a], missing [b]")
    }

    @Test
    fun `shouldContainExactly passes the same elements in order and carries both lists for a diff`() {
        setOf(1, 2) shouldContainExactly listOf(1, 2)
        listOf("a", "b").shouldContainExactly("a", "b")

        val thrown = shouldThrow<AssertionFailedError> { listOf(1, 2) shouldContainExactly listOf(2, 1) }
        thrown.message!!.lines().take(2) shouldBe listOf("expected: [2, 1]", "but was:  [1, 2]")
        thrown.expected.value shouldBe listOf(2, 1)
        thrown.actual.value shouldBe listOf(1, 2)
        failure { listOf("a").shouldContainExactly("a", "b") } shouldBe listOf("expected: [a, b]", "but was:  [a]")
    }

    @Test
    fun `shouldContainExactlyInAnyOrder ignores order but counts repeats`() {
        listOf(1, 2, 2) shouldContainExactlyInAnyOrder listOf(2, 1, 2)
        listOf("b", "a").shouldContainExactlyInAnyOrder("a", "b")
        failure { listOf(1, 2, 2) shouldContainExactlyInAnyOrder listOf(1, 1, 2) } shouldBe
            listOf("expected: [1, 1, 2] in any order", "but was:  [1, 2, 2]")
        failure { listOf("a").shouldContainExactlyInAnyOrder("a", "b") } shouldBe
            listOf("expected: [a, b] in any order", "but was:  [a]")
    }

    @Test
    fun `shouldContainOnly ignores order and repeats, but not a stray or a missing element`() {
        listOf("text", "text") shouldContainOnly setOf("text")
        failure { listOf(1, 2) shouldContainOnly setOf(1) } shouldBe
            listOf("expected: only and all of [1]", "but was:  [1, 2]")
        failure { listOf(1) shouldContainOnly setOf(1, 2) } shouldBe
            listOf("expected: only and all of [1, 2]", "but was:  [1]")
    }

    @Test
    fun `a failure names the line of the test that checked`() {
        val thrown = shouldThrow<AssertionFailedError> { listOf(1) shouldContain 2 }
        thrown.message!!.lines()[2].startsWith("at CollectionsTest.kt:") shouldBe true
        thrown.stackTrace.first().className shouldBe CollectionsTest::class.java.name
    }
}
