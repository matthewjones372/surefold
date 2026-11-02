package io.github.matthewjones372.surefold.app.typesafe

import com.typesafe.config.ConfigFactory
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.withClue

/** What a section read is what its report prints, so the record has to be exact (spec 0155). */
object PathsReadSuite : Suite() {
    private val hocon = ConfigFactory.parseString(
        """
        petshop {
          host = "0.0.0.0", port = 9090, unread = true
          database { url = "jdbc:postgresql://user:pass@db/shop", poolSize = 4 }
        }
        """.trimIndent(),
    )

    init {
        test("a section lists the paths its leaves read, nested ones in full, and nothing else") {
            val section = hocon.sectionOf("petshop") {
                string("host") to int("port") to section("database") { secret("url") to int("poolSize") }
            }

            withClue("petshop.unread is under the section but was never read") {
                section.paths shouldContainExactly listOf(
                    PathRead("petshop.host", secret = false),
                    PathRead("petshop.port", secret = false),
                    PathRead("petshop.database.url", secret = true),
                    PathRead("petshop.database.poolSize", secret = false),
                )
            }
        }

        test("a path that could not be read is still one the section asked for") {
            val section = hocon.sectionOf("petshop") { string("host") to int("missing") }

            section.paths shouldContainExactly listOf(
                PathRead("petshop.host", secret = false),
                PathRead("petshop.missing", secret = false),
            )
        }

        test("a path read twice is listed once, and as a secret if either read was one") {
            val section = hocon.sectionOf("petshop") { string("host") to secret("host") }

            section.paths shouldContainExactly listOf(PathRead("petshop.host", secret = true))
        }

        test("a value read through of with its path is recorded like a leaf") {
            val section = hocon.sectionOf("petshop") { of("unread", false) { getBoolean("unread") } }

            section.paths shouldContainExactly listOf(PathRead("petshop.unread", secret = false))
        }

        test("a quoted path is the same setting as its plain one, recorded once and secret if either read was") {
            val section = hocon.sectionOf("petshop") { string("host") to secret("\"host\"") }

            section.paths shouldContainExactly listOf(PathRead("petshop.host", secret = true))
        }
    }
}
