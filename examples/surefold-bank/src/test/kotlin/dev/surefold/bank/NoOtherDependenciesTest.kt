package dev.surefold.bank

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What the bank runs on, stated as a test: an application may choose its dependencies, but it says which. */
class NoOtherDependenciesTest {

    // The journal on Postgres, with the one jar its driver brings, through HikariCP and the logging API it brings;
    // Liquibase, which applies the journal's changelog, with the jars it brings; and kotlinx.serialization's JSON.
    private val allowed = listOf(
        "kotlin-stdlib", "annotations-", "surefold", "postgresql-", "checker-qual-", "HikariCP-", "slf4j-api-",
        "liquibase-core-", "opencsv-", "snakeyaml-", "jaxb-api-", "commons-collections4-", "commons-text-",
        "commons-lang3-", "commons-io-",
        "kotlinx-serialization-",
    )

    @Test
    fun `the main runtime classpath is surefold-cluster, the JDBC journal, Postgres, Liquibase and JSON`() {
        val raw = System.getProperty("surefold.bank.runtimeClasspath")
        withClue("the build must pass -Dsurefold.bank.runtimeClasspath; see surefold-bank/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-bank must stay surefold-cluster, the journal and what it runs on, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
