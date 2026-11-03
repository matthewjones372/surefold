package dev.surefold.sqs

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBe
import java.io.File

object SqsGuideSuite : Suite() {
    private fun marked(name: String) = Regex("""<!-- $name -->\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)

    init {
        mapOf("sqs-guide" to "GuideExample.kt", "sqs-testing" to "GuideTesting.kt").forEach { (marker, example) ->
            test("the SQS guide's $marker example is compiled from its imports down") {
                val root = System.getProperty("surefold.sqs.repoRoot")
                expect(root != null) { "the build must pass -Dsurefold.sqs.repoRoot" }
                val guide = File(root!!, "docs/sqs.md").readText()
                val fences = marked(marker).findAll(guide).map { it.groupValues[1] }.toList()
                fences.size shouldBe 1
                val source = File(root, "$EXAMPLES/$example").readText()
                source.substring(source.indexOf("import ")).trim() shouldBe fences.single().trim()
            }
        }
    }

    private const val EXAMPLES = "integrations/surefold-sqs/src/test/kotlin/dev/surefold/sqs/guide"
}
