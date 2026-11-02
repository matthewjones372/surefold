package io.github.matthewjones372.surefold.app.typesafe

import com.typesafe.config.ConfigFactory
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import io.github.matthewjones372.surefold.app.testApp
import io.github.matthewjones372.surefold.app.use
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private data class Wanted(val url: String, val poolSize: Int, val idle: Duration)

/** Where a test's configuration comes from, and what it has to restate to change one key. */
object SourcesSuite : Suite() {
    private val section = config<Wanted>("database") {
        Wanted(string("url"), int("poolSize"), duration("idle"))
    }

    init {
        test("a document written where the test is") {
            val app = configOf(
                """
                database { url = "jdbc:postgresql://db/app", poolSize = 2, idle = 1s }
                """.trimIndent(),
            ) + section

            testApp(app) { wanted: Wanted -> wanted } shouldBe Wanted("jdbc:postgresql://db/app", 2, 1.seconds)
        }

        test("a file on the classpath") {
            val app = configFromResource("application.conf") + section

            testApp(app) { wanted: Wanted -> wanted.poolSize } shouldBe 5
        }

        test("one key changed, and the file keeping the rest") {
            val service = configFromResource("application.conf") + section

            val underTest = service.overridingConfig(
                """database.poolSize = 1""",
                fallback = ConfigFactory.parseResources("application.conf").resolve(),
            )

            val wanted = testApp(underTest) { wanted: Wanted -> wanted }

            wanted.poolSize shouldBe 1
            withClue("the url and the idle came from the file, unrestated") {
                wanted.url shouldBe "jdbc:postgresql://db.internal/shopping"
                wanted.idle shouldBe 30.seconds
            }
        }

        test("what the service itself reads") {
            val app = loadedConfig() + config<String>("petshop") { string("nothing") }

            withClue("application.conf on this classpath has no petshop section") {
                app.use { read: String -> read }.isLeft() shouldBe true
            }
        }
    }
}
