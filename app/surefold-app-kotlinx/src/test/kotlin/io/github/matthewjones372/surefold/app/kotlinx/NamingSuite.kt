package io.github.matthewjones372.surefold.app.kotlinx

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.app.testApp
import io.github.matthewjones372.surefold.app.typesafe.configOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Serializable
private data class Kebabed(
    val arrivalsEvery: Duration,
    @SerialName("outbox_every") val outboxEvery: Duration,
    val nestedSection: Inner,
)

@Serializable
private data class Inner(val maxRetries: Int)

@Serializable
private data class Renamed(@SerialName("outboxEvery") val outbox: Duration)

object NamingSuite : Suite() {
    init {
        test("under kebab naming arrivalsEvery reads arrivals-every, and a SerialName is the key as written") {
            val file = configOf(
                """
                petshop {
                  arrivals-every = 30s
                  outbox_every = 5s
                  nested-section { max-retries = 3 }
                }
                """,
            )

            val settings = testApp(file + config<Kebabed>("petshop", Naming.Kebab)) { settings: Kebabed -> settings }

            settings shouldBe Kebabed(30.seconds, 5.seconds, Inner(3))
        }

        test("under kebab naming a SerialName with capitals is kebab-cased, as kotlinx keeps no other name") {
            val file = configOf("petshop { outbox-every = 5s }")

            val settings = testApp(file + config<Renamed>("petshop", Naming.Kebab)) { settings: Renamed -> settings }

            settings shouldBe Renamed(5.seconds)
        }
    }
}
