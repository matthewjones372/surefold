package dev.surefold.sqs

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBe
import java.io.File

object SqsPekkoGuideSuite : Suite() {
    private fun marked(name: String) = Regex("""<!-- $name -->\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)

    init {
        mapOf("sqs-pekko" to "GuideOnPekko.kt").forEach { (marker, example) ->
            test("the SQS guide's $marker example is compiled from its imports down") {
                val root = System.getProperty("surefold.sqs.pekko.repoRoot")
                expect(root != null) { "the build must pass -Dsurefold.sqs.pekko.repoRoot" }
                val guide = File(root!!, "docs/sqs.md").readText()
                val fences = marked(marker).findAll(guide).map { it.groupValues[1] }.toList()
                fences.size shouldBe 1
                val source = File(root, "$EXAMPLES/$example").readText()
                source.substring(source.indexOf("import ")).trim() shouldBe fences.single().trim()
            }
        }
    }

    private const val EXAMPLES = "integrations/surefold-sqs-pekko/src/test/kotlin/dev/surefold/sqs/guide"
}
