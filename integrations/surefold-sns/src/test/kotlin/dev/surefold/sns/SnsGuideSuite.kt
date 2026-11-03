package dev.surefold.sns

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBe
import java.io.File

object SnsGuideSuite : Suite() {
    private fun marked(name: String) = Regex("""<!-- $name -->\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)

    init {
        mapOf("sns-guide" to "GuideExample.kt", "sns-testing" to "GuideTesting.kt").forEach { (marker, example) ->
            test("the SNS guide's $marker example is compiled from its imports down") {
                val root = System.getProperty("surefold.sns.repoRoot")
                expect(root != null) { "the build must pass -Dsurefold.sns.repoRoot" }
                val guide = File(root!!, "docs/sns.md").readText()
                val fences = marked(marker).findAll(guide).map { it.groupValues[1] }.toList()
                fences.size shouldBe 1
                val source = File(root, "$EXAMPLES/$example").readText()
                source.substring(source.indexOf("import ")).trim() shouldBe fences.single().trim()
            }
        }
    }

    private const val EXAMPLES = "integrations/surefold-sns/src/test/kotlin/dev/surefold/sns/guide"
}
