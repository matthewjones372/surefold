package dev.surefold.app.liquibase

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
        // Liquibase and what it brings. No driver and no DataSource: both are
        // the application's, and so is what they connect to.
        "liquibase-core",
        "opencsv",
        "commons-lang3",
        "commons-text",
        "commons-collections4",
        "commons-io",
        "snakeyaml",
        // Liquibase reads its XML changelogs through JAXB.
        "jaxb-api",
    )

    @Test
    fun `the main runtime classpath is surefold-app and liquibase-core, and nothing else`() {
        val raw = System.getProperty("surefold.app.liquibase.runtimeClasspath")
        withClue("the build must pass -Dsurefold.app.liquibase.runtimeClasspath; see the build file") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-app-liquibase must stay surefold-app plus liquibase-core, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
