package dev.surefold.sns

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBe
import java.io.File

object SnsGuideSuite : Suite() {
    private val marked = Regex("""<!-- sns-guide -->\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)

    init {
        test("the SNS guide example is compiled from its imports down") {
            val root = System.getProperty("surefold.sns.repoRoot")
            expect(root != null) { "the build must pass -Dsurefold.sns.repoRoot" }
            val fences = marked.findAll(File(root!!, "docs/sns.md").readText()).map { it.groupValues[1] }.toList()
            fences.size shouldBe 1
            val source = File(root, EXAMPLE).readText()
            source.substring(source.indexOf("import ")).trim() shouldBe fences.single().trim()
        }
    }

    private const val EXAMPLE = "integrations/surefold-sns/src/test/kotlin/dev/surefold/sns/guide/GuideExample.kt"
}
