package dev.surefold.app.flyway

import dev.surefold.actor.journal.jdbc.Postgres
import dev.surefold.actor.journal.jdbc.migrate
import dev.surefold.app.single
import dev.surefold.app.testApp
import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBe
import java.io.File
import javax.sql.DataSource

/** docs/flyway.md's example is this module's GuideExample.kt, and it runs. */
object FlywayGuideSuite : Suite() {
    private val marked = Regex("""<!-- flyway -->\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)

    init {
        test("the guide's example is GuideExample.kt from its imports down") {
            val root = System.getProperty("surefold.app.flyway.repoRoot")
            expect(root != null) { "the build must pass -Dsurefold.app.flyway.repoRoot; see its build.gradle.kts" }
            val fences = marked.findAll(File(root!!, "docs/flyway.md").readText()).map { it.groupValues[1] }.toList()
            fences.size shouldBe 1
            val source = File(root, EXAMPLE).readText()
            source.substring(source.indexOf("import ")).trim() shouldBe fences.single().trim()
        }

        test("the guide's example hands over a database Liquibase migrated, and its reader starts after") {
            val source = Postgres.empty().also { it.migrate("db/liquibase/changelog.yaml") }

            testApp(single<DataSource> { source } + database) { orders: OrderRepo ->
                orders.source.hasTable("flyway_schema_history")
            } shouldBe true
        }
    }

    private const val EXAMPLE =
        "app/surefold-app-flyway/src/test/kotlin/dev/surefold/app/flyway/GuideExample.kt"
}
