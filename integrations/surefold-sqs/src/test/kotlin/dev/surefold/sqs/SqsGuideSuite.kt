package dev.surefold.sqs

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBe
import java.io.File

object SqsGuideSuite : Suite() {
    private val marked = Regex("""<!-- sqs-guide -->\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)

    init {
        test("the SQS guide example is compiled from its imports down") {
            val root = System.getProperty("surefold.sqs.repoRoot")
            expect(root != null) { "the build must pass -Dsurefold.sqs.repoRoot" }
            val fences = marked.findAll(File(root!!, "docs/sqs.md").readText()).map { it.groupValues[1] }.toList()
            fences.size shouldBe 1
            val source = File(root, EXAMPLE).readText()
            source.substring(source.indexOf("import ")).trim() shouldBe fences.single().trim()
        }
    }

    private const val EXAMPLE = "integrations/surefold-sqs/src/test/kotlin/dev/surefold/sqs/guide/GuideExample.kt"
}
