package dev.surefold.assert

import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError

class StringsTest {

    private fun failure(check: () -> Unit): List<String> =
        shouldThrow<AssertionFailedError> { check() }.message!!.lines().take(2)

    @Test
    fun `shouldContain returns the string, and quotes both when it fails`() {
        ("a lost ask" shouldContain "lost") shouldBe "a lost ask"
        failure { "an answer" shouldContain "lost" } shouldBe
            listOf("expected: a string containing \"lost\"", "but was:  \"an answer\"")
    }

    @Test
    fun `a nullable string that is null fails rather than passing`() {
        val message: String? = null
        failure { message shouldContain "lost" } shouldBe
            listOf("expected: a string containing \"lost\"", "but was:  null")
    }

    @Test
    fun `shouldNotContain refuses the part it names`() {
        "an answer" shouldNotContain "lost"
        failure { "a lost ask" shouldNotContain "lost" } shouldBe
            listOf("expected: a string without \"lost\"", "but was:  \"a lost ask\"")
    }

    @Test
    fun `shouldStartWith and shouldEndWith look at each end`() {
        "surefold-actor" shouldStartWith "surefold"
        "surefold-actor" shouldEndWith "actor"
        failure { "surefold-actor" shouldStartWith "actor" } shouldBe
            listOf("expected: a string starting with \"actor\"", "but was:  \"surefold-actor\"")
        failure { "surefold-actor" shouldEndWith "surefold" } shouldBe
            listOf("expected: a string ending with \"surefold\"", "but was:  \"surefold-actor\"")
    }

    @Test
    fun `shouldNotBeBlank refuses whitespace alone`() {
        " x ".shouldNotBeBlank() shouldBe " x "
        failure { "  ".shouldNotBeBlank() } shouldBe listOf("expected: a string that is not blank", "but was:  \"  \"")
    }

    @Test
    fun `shouldMatch needs the whole string to match`() {
        "shard-253" shouldMatch "shard-\\d+"
        "shard-253" shouldMatch Regex("[a-z]+-\\d{3}")
        failure { "shard-253/o-1" shouldMatch "shard-\\d+" } shouldBe
            listOf("expected: a string matching /shard-\\d+/", "but was:  \"shard-253/o-1\"")
    }

    @Test
    fun `shouldContain with a pattern needs a match only somewhere in the string`() {
        ("map  Stage.kt:12" shouldContain Regex("""Stage\.kt:\d+""")) shouldBe "map  Stage.kt:12"
        failure { "map  Stage.kt" shouldContain Regex("""Stage\.kt:\d+""") } shouldBe
            listOf("expected: a string containing a match for /Stage\\.kt:\\d+/", "but was:  \"map  Stage.kt\"")
    }
}
