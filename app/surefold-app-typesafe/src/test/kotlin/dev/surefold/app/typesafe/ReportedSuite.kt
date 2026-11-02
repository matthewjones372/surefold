package dev.surefold.app.typesafe

import com.typesafe.config.ConfigFactory
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.withClue
import java.io.File

/**
 * The report the cookbook prints, held to what the code answers. A sample of an output is worth
 * having in a page only while it is the output.
 */
object ReportedSuite : Suite() {
    private val stacked = layeredConfigOf(
        "application.conf" to ConfigFactory.parseResources("demo/application.conf"),
        "reference.conf" to ConfigFactory.parseResources("demo/reference.conf"),
    )

    /** What the fence marked [marker] in docs/cookbook.md holds, handed to [then] once it is found. */
    private fun printed(marker: String = "cookbook-config-report", then: (String) -> Unit) {
        val root: String? = System.getProperty("surefold.app.typesafe.repoRoot")
        if (root == null) {
            val clue =
                "the build must pass -Dsurefold.app.typesafe.repoRoot; see surefold-app-typesafe/build.gradle.kts"
            return withClue(clue) { root.shouldNotBeNull() }
        }
        val fenced = Regex("""<!-- $marker -->\s*```\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)
        val found = fenced.findAll(File(root, "docs/cookbook.md").readText()).map { it.groupValues[1] }.toList()
        if (found.size != 1) {
            return withClue("docs/cookbook.md must carry one fence marked <!-- $marker -->") {
                found.size shouldBe 1
            }
        }
        return then(found.single())
    }

    init {
        test("the cookbook prints the report this stack answers") {
            printed { page -> stacked.origins().report() shouldBe page }
        }

        test("the cookbook prints the block a section logs") {
            val read = stacked.config.sectionOf("petshop") {
                int("port") to section("db") { int("pool") to secret("password") }
            }

            printed("cookbook-section-report") { page -> stacked.config.reportOf("petshop", read.paths) shouldBe page }
        }
    }
}
