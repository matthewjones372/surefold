package dev.surefold.app.kotlinx

import dev.surefold.app.StartupError
import dev.surefold.app.testApp
import dev.surefold.app.typesafe.configOf
import dev.surefold.app.use
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.withClue
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Serializable
private data class Db(val url: String, val replicas: List<String>)

@Serializable
private data class Settings(
    val host: String,
    val port: Int,
    val limit: Long,
    val verbose: Boolean,
    val arrivalsEvery: Duration,
    val db: Db,
)

@Serializable
private data class Defaulted(
    val port: Int = 8080,
    val proxy: String? = null,
    val fallback: String?,
    val timeout: Duration? = null,
)

@Serializable
private data class Unreadable(
    val host: String,
    val ratio: Double,
    val weight: Float,
    val small: Short,
    val tiny: Byte,
    val initial: Char,
    val ports: List<Int>,
    val tags: Set<String>,
    val limits: Map<String, Int>,
    val spare: Double? = null,
)

object ClassConfigSuite : Suite() {
    init {
        test("a class with every supported leaf reads from HOCON") {
            val file = configOf(
                """
                petshop {
                  host = "shop.internal"
                  port = 8081
                  limit = 5000000000
                  verbose = true
                  arrivalsEvery = 30s
                  db { url = "jdbc:postgresql://db/shop", replicas = [one, two] }
                }
                """,
            )

            val settings = testApp(file + config<Settings>("petshop")) { settings: Settings -> settings }

            settings shouldBe Settings(
                host = "shop.internal",
                port = 8081,
                limit = 5_000_000_000,
                verbose = true,
                arrivalsEvery = 30.seconds,
                db = Db("jdbc:postgresql://db/shop", listOf("one", "two")),
            )
        }

        test("an absent path is the property's default, and an absent nullable is null") {
            val file = configOf("petshop { timeout = 5s }")

            val settings = testApp(file + config<Defaulted>("petshop")) { settings: Defaulted -> settings }

            settings shouldBe Defaulted(port = 8080, proxy = null, fallback = null, timeout = 5.seconds)
        }

        test("a file with three faults is refused naming all three") {
            val file = configOf(
                """
                petshop {
                  host = "shop.internal"
                  port = eighty
                  limit = 1
                  verbose = true
                  arrivalsEvery = soon
                  db { replicas = [one] }
                }
                """,
            )

            (file + config<Settings>("petshop")).use { _: Settings -> }.leftOrNull().shouldNotBeNull { error ->
                error.shouldBeInstanceOf<StartupError.Refused> { refused ->
                    val reason = refused.reason
                    withClue(
                        "a port that is not a number, a duration that is not one, and a url missing a section down",
                    ) {
                        reason shouldContain "port"
                        reason shouldContain "arrivalsEvery"
                        reason shouldContain "url"
                    }
                }
            }
        }

        test("a property of a type the class form cannot read is a fault naming its type, beside every other") {
            val file = configOf(
                """
                petshop {
                  ratio = 0.5, weight = 1.5, small = 1, tiny = 1, initial = x
                  ports = [1, 2], tags = [a], limits { a = 1 }
                }
                """,
            )

            (file + config<Unreadable>("petshop")).use { _: Unreadable -> }.leftOrNull().shouldNotBeNull { error ->
                error.shouldBeInstanceOf<StartupError.Refused> { refused ->
                    val reason = refused.reason
                    withClue(reason) {
                        reason shouldContain "host"
                        reason shouldContain "'ratio': is a Double"
                        reason shouldContain "'weight': is a Float"
                        reason shouldContain "'small': is a Short"
                        reason shouldContain "'tiny': is a Byte"
                        reason shouldContain "'initial': is a Char"
                        reason shouldContain "'ports': is a List<Int>"
                        reason shouldContain "'tags': is a Set<String>"
                        reason shouldContain "'limits': is a Map<String, Int>"
                        reason shouldContain "'spare': is a Double?"
                    }
                }
            }
        }
    }
}
