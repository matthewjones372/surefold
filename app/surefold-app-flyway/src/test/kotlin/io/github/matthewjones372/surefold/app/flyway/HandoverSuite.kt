package io.github.matthewjones372.surefold.app.flyway

import dev.surefold.actor.journal.jdbc.Postgres
import dev.surefold.actor.journal.jdbc.migrate
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.withClue
import io.github.matthewjones372.surefold.app.Migrated
import io.github.matthewjones372.surefold.app.single
import io.github.matthewjones372.surefold.app.use
import javax.sql.DataSource

private const val SERVICE = "classpath:db/handover"

/** The release after: the same V1, and V2 dropping Liquibase's tables. */
private const val DROPPED = "classpath:db/dropped"
private const val LAST = "shop:0014-orders"

/** A database a service on Liquibase has migrated until now: jobs' changelog, then its own. */
private fun onLiquibase(): DataSource = Postgres.empty().also { it.migrate("db/liquibase/changelog.yaml") }

private fun DataSource.history(table: String): List<String> =
    strings("select type || ' ' || coalesce(version, 'R') from $table order by installed_rank")

/** A service moving from Liquibase to Flyway (spec 0242), as docs/flyway.md takes it through it. */
object HandoverSuite : Suite() {
    init {
        test("a database migrated by Liquibase is baselined, then dropped of Liquibase's tables") {
            val source = onLiquibase()

            migrate(source, SurefoldSchema.JOBS, service = SERVICE, fromLiquibase = LAST) shouldBe 0
            withClue("the service starts at V1, its dump; jobs at the script of its last changeset, jobs-operations") {
                source.history("flyway_schema_history") shouldBe listOf("BASELINE 1")
                source.history("flyway_surefold_jobs") shouldBe listOf("BASELINE 6")
            }
            source.hasTable("databasechangelog") shouldBe true

            migrate(source, SurefoldSchema.JOBS, service = DROPPED, fromLiquibase = LAST) shouldBe 1
            source.history("flyway_schema_history") shouldBe listOf("BASELINE 1", "SQL 2")
            source.hasTable("databasechangelog") shouldBe false
            source.hasTable("databasechangeloglock") shouldBe false
            withClue("with Liquibase's tables gone, fromLiquibase can go too") {
                migrate(source, SurefoldSchema.JOBS, service = DROPPED) shouldBe 0
            }
        }

        test("the schema handed over is the one a new database gets from Flyway alone") {
            val handedOver = onLiquibase().also {
                migrate(it, SurefoldSchema.JOBS, service = DROPPED, fromLiquibase = LAST)
            }
            val fresh = Postgres.empty().also { migrate(it, SurefoldSchema.JOBS, service = DROPPED) }

            catalog(handedOver) shouldBe catalog(fresh)
        }

        test("a database whose last changeset is not the one named is refused, and nothing is changed") {
            val source = onLiquibase()
            val app = single<DataSource> { source } +
                flyway(SurefoldSchema.JOBS, service = SERVICE, fromLiquibase = "shop:0013-customers") +
                single { done: Migrated -> done.applied }

            app.use { applied: Int -> applied }.leftOrNull().shouldNotBeNull { refused ->
                refused.toString() shouldContain "the last changeset Liquibase applied here is $LAST"
            }
            source.hasTable("flyway_schema_history") shouldBe false
            source.hasTable("flyway_surefold_jobs") shouldBe false
            source.hasTable("databasechangelog") shouldBe true
        }
    }
}
