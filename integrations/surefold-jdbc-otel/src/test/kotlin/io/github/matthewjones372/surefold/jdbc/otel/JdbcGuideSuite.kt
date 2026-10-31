package io.github.matthewjones372.surefold.jdbc.otel

import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.expect
import io.github.matthewjones372.surefold.junit.shouldBe
import java.io.File

object JdbcGuideSuite : Suite() {
    private val marked = Regex("""<!-- jdbc-tracing -->\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)

    init {
        test("the JDBC guide example is compiled from its imports down") {
            val root = System.getProperty("surefold.jdbc.otel.repoRoot")
            expect(root != null) { "the build must pass -Dsurefold.jdbc.otel.repoRoot" }
            val fences = marked.findAll(File(root!!, "docs/jdbc-tracing.md").readText())
                .map { it.groupValues[1] }.toList()
            fences.size shouldBe 1
            val source = File(root, EXAMPLE).readText()
            source.substring(source.indexOf("import ")).trim() shouldBe fences.single().trim()
        }
    }

    private const val EXAMPLE =
        "integrations/surefold-jdbc-otel/src/test/kotlin/" +
            "io/github/matthewjones372/surefold/jdbc/otel/guide/GuideExample.kt"
}
