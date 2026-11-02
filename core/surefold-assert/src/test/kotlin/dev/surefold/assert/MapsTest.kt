package dev.surefold.assert

import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError

class MapsTest {

    private fun failure(check: () -> Unit): List<String> =
        shouldThrow<AssertionFailedError> { check() }.message!!.lines().take(2)

    private val tags = mapOf("request_id" to "r-1", "user" to "ada")

    @Test
    fun `shouldBeEmpty passes no entries and shows the ones it found`() {
        emptyMap<String, Int>().shouldBeEmpty()
        failure { mapOf("a" to 1).shouldBeEmpty() } shouldBe listOf("expected: no entries", "but was:  {a=1}")
    }

    @Test
    fun `shouldNotBeEmpty passes an entry and says it found none`() {
        tags.shouldNotBeEmpty() shouldBe tags
        failure { emptyMap<String, Int>().shouldNotBeEmpty() } shouldBe
            listOf("expected: at least one entry", "but was:  {}")
    }

    @Test
    fun `shouldContainKey passes a key that is there and names the one that is not`() {
        tags shouldContainKey "user"
        failure { mapOf("a" to 1) shouldContainKey "b" } shouldBe
            listOf("expected: a map with the key b", "but was:  {a=1}")
    }

    @Test
    fun `shouldNotContainKey passes a key that is not there and names the one that is`() {
        tags shouldNotContainKey "trace_id"
        failure { mapOf("a" to 1) shouldNotContainKey "a" } shouldBe
            listOf("expected: a map without the key a", "but was:  {a=1}")
    }

    @Test
    fun `shouldContain passes an entry that is there and names the one that is not`() {
        tags shouldContain ("request_id" to "r-1")
        failure { tags shouldContain ("request_id" to "r-2") } shouldBe
            listOf("expected: a map with request_id=r-2", "but was:  {request_id=r-1, user=ada}")
    }

    @Test
    fun `shouldContain tells a key mapped to null from a missing key`() {
        val sparse: Map<String, String?> = mapOf("a" to null)
        sparse shouldContain ("a" to null)
        failure { sparse shouldContain ("b" to null) } shouldBe
            listOf("expected: a map with b=null", "but was:  {a=null}")
    }

    @Test
    fun `shouldContainAll passes a subset of the entries and names what is missing`() {
        tags shouldContainAll mapOf("user" to "ada")
        failure { tags shouldContainAll mapOf("user" to "bob", "request_id" to "r-1") } shouldBe
            listOf(
                "expected: all of {user=bob, request_id=r-1}",
                "but was:  {request_id=r-1, user=ada}, missing {user=bob}",
            )
    }

    @Test
    fun `shouldContainExactly passes the same entries and carries both maps for a diff`() {
        tags shouldContainExactly mapOf("user" to "ada", "request_id" to "r-1")

        val thrown = shouldThrow<AssertionFailedError> { mapOf("a" to 1) shouldContainExactly mapOf("a" to 2) }
        thrown.message!!.lines().take(2) shouldBe listOf("expected: {a=2}", "but was:  {a=1}")
        thrown.expected.value shouldBe mapOf("a" to 2)
        thrown.actual.value shouldBe mapOf("a" to 1)
    }
}
