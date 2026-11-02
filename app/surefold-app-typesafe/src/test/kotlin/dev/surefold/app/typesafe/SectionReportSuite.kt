package dev.surefold.app.typesafe

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import dev.surefold.app.StartupError
import dev.surefold.app.single
import dev.surefold.app.testApp
import dev.surefold.app.use
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldHaveSize
import dev.surefold.junit.shouldNotContain
import dev.surefold.junit.withClue
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

private class WholeDb(val settings: Config)

private data class Shop(val host: String, val port: Int, val url: Secret, val password: String)

/** What one test's handler on the `surefold-app` logger heard. */
private class Heard {
    private val logged = mutableListOf<String>()

    val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            synchronized(logged) { logged += record.message }
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    fun blocks(): List<String> = synchronized(logged) { logged.filter { "configuration: petshop" in it } }

    fun block(): String = synchronized(logged) { logged.single { "configuration: petshop" in it } }
}

/** Each section logs what it read, as it reads it, and nothing it did not (spec 0155). */
object SectionReportSuite : Suite(concurrent = false) {
    // Not concurrent: nine of the eleven tests check what a handler on the one process-wide `surefold-app` logger
    // heard, mostly that it heard a single block, and a test starting an app beside them logs its own block there.
    private val logger = Logger.getLogger("surefold-app")

    /** Runs [body] with a handler on the `surefold-app` logger, removed again once it returns. */
    private fun listening(body: (Heard) -> Unit) {
        val heard = Heard()
        logger.addHandler(heard.handler)
        try {
            return body(heard)
        } finally {
            logger.removeHandler(heard.handler)
        }
    }

    private val hocon =
        """
        petshop {
          host = "0.0.0.0", port = 9090, unread = "left alone"
          url = "jdbc:postgresql://user:hunter2@db/shop", password = "hunter3"
        }
        """.trimIndent()

    private val shop = config<Shop>("petshop") {
        Shop(string("host"), int("port"), secret("url"), string("password"))
    }

    init {
        test("a section logs the paths it read, masked by how they were read and by name") {
            listening { heard ->
                testApp(configOf(hocon) + shop) { _: Shop -> Unit }

                val block = heard.block()
                block shouldContain "petshop.host"
                block shouldContain "9090"
                withClue("only what the section read is printed") { block shouldNotContain "unread" }
                withClue("the url's name says nothing; secret() is what masks it") {
                    block shouldNotContain "hunter2"
                }
                withClue("Secrets.default still masks a password read as a plain string") {
                    block shouldNotContain "hunter3"
                }
                block shouldContain MASK
            }
        }

        test("a section over layers says what each setting overrode") {
            listening { heard ->
                val base = layeredConfigOf("base" to ConfigFactory.parseString(hocon))
                val app = configOf(hocon).overridingConfig("petshop.port = 8081", fallback = base) + shop

                testApp(app) { _: Shop -> Unit }

                heard.block() shouldContain "overrides 9090"
            }
        }

        test("a layered merge is a Config Typesafe can merge under another, and still reports its layers") {
            listening { heard ->
                val base = layeredConfigOf("base" to ConfigFactory.parseString(hocon))

                val clue = "withFallback casts its argument to Typesafe's own Config, so the merge has to be one"
                withClue(clue) {
                    val above = ConfigFactory.parseString("petshop.port = 8081")
                    above.withFallback(base.config).getInt("petshop.port") shouldBe 8081
                }
                val app = configOf(hocon).overridingConfig("petshop.port = 8082", fallback = base.config) + shop
                testApp(app) { _: Shop -> Unit }

                heard.block() shouldContain "8082"
            }
        }

        test("a setting read by its quoted path is masked as its plain one would be") {
            listening { heard ->
                val quoted = config<String>("petshop") { string("\"password\"") }

                testApp(configOf(hocon) + quoted) { _: String -> Unit }

                heard.block() shouldNotContain "hunter3"
            }
        }

        test("an object read whole is reported as its settings, each masked as it would be alone") {
            listening { heard ->
                val whole = config<WholeDb>("petshop") { WholeDb(of("db", ConfigFactory.empty()) { getConfig("db") }) }
                val nested = """petshop { db { user = "shop", password = "hunter4" } }"""

                testApp(configOf(nested) + whole) { _: WholeDb -> Unit }

                val block = heard.block()
                block shouldContain "petshop.db.user"
                withClue("a password under an object read whole is still a password") {
                    block shouldNotContain "hunter4"
                }
            }
        }

        test("two stacks that merge to equal documents each keep their own layers") {
            listening { _ ->
                val first = layeredConfigOf("first" to ConfigFactory.parseString(hocon))
                val second = layeredConfigOf("second" to ConfigFactory.parseString(hocon))

                val clue = "Typesafe's equals ignores origins, so the two merges are equal and still not the same stack"
                withClue(clue) { first.config shouldBe second.config }
                first.config.layeredOrNull()?.layers?.map { it.name } shouldBe listOf("first")
                second.config.layeredOrNull()?.layers?.map { it.name } shouldBe listOf("second")
            }
        }

        test("a path HOCON cannot parse refuses the start with its fault, not the report's") {
            listening { _ ->
                val app = configOf(hocon) + config<String>("petshop") { string("not..a.path") }

                val refused = app.use { _: String -> Unit }.leftOrNull()

                refused.shouldBeInstanceOf<StartupError.Refused>()
            }
        }

        test("a setting a lower layer left null, overridden above, still starts and says so") {
            listening { heard ->
                val base = layeredConfigOf("base" to ConfigFactory.parseString(hocon + "\npetshop.port = null"))
                val app = configOf(hocon).overridingConfig("petshop.port = 8081", fallback = base) + shop

                testApp(app) { _: Shop -> Unit }

                heard.block() shouldContain "overrides null"
            }
        }

        test("a hand-written Config, with no layers, is still reported") {
            listening { heard ->
                val app = single<Config> { ConfigFactory.parseString(hocon).resolve() } + shop

                testApp(app) { _: Shop -> Unit }

                heard.block() shouldContain "petshop.port"
            }
        }

        test("a section that refuses the start logs what it did read first") {
            listening { heard ->
                val app = configOf("""petshop { host = "0.0.0.0" }""") + shop

                val refused = app.use { _: Shop -> Unit }.leftOrNull()

                val block = heard.block()
                refused.shouldBeInstanceOf<StartupError.Refused>()
                block shouldContain "petshop.host"
                withClue("a path asked for and absent says so, rather than vanishing from the report") {
                    block shouldContain "petshop.port"
                }
            }
        }

        test("a section chosen on logs its block when the graph starts") {
            listening { heard ->
                val root = ConfigFactory.parseString(hocon)
                val app = configOf(root) + root.choosing("petshop", { string("host") }) { single<Int> { 1 } }

                testApp(app) { host: String -> host } shouldBe "0.0.0.0"
                heard.blocks() shouldHaveSize 1
            }
        }
    }
}
