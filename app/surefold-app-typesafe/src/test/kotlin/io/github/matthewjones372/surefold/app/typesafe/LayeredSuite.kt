package io.github.matthewjones372.surefold.app.typesafe

import com.typesafe.config.ConfigFactory
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeNull
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.shouldNotContain
import dev.surefold.junit.withClue

/** What a merge threw away, kept, because that is the half that answers "why is it this?". */
object LayeredSuite : Suite() {
    private val reference = ConfigFactory.parseString("petshop { port = 8080, db { pool = 4 } }")

    private val application = ConfigFactory.parseString("petshop { port = 9090 }")

    private fun layered() = layeredConfigOf(
        "application.conf" to application,
        "reference.conf" to reference,
    )

    /** The HOCON override pair, `a = "x"` then `a = ${'$'}{?ENV}`, which Typesafe holds as a delayed merge. */
    private fun overridden(env: String) = layeredConfigOf(
        "application.conf" to ConfigFactory.parseString(
            """
            petshop.bus = "in-process"
            petshop.bus = ${'$'}{?$env}
            """.trimIndent(),
        ),
        "reference.conf" to ConfigFactory.parseString("""petshop.bus = "kafka" """),
    )

    init {
        test("the merge is what the layers resolve to") {
            layered().config.getInt("petshop.port") shouldBe 9090
        }

        test("a setting that was overridden names what it replaced") {
            val port = layered().origins().single { it.path == "petshop.port" }

            port.value shouldBe "9090"
            port.overrides.shouldNotBeNull { was -> was.value shouldBe "8080" }
        }

        test("a setting only one layer supplied says nothing extra") {
            layered().origins().single { it.path == "petshop.db.pool" }.overrides.shouldBeNull()
        }

        test("the report says what a setting overrode") {
            val said = layered().origins().report()

            said shouldContain "petshop.port"
            said shouldContain "overrides 8080"
        }

        test("an overridden secret does not print either value") {
            val layers = layeredConfigOf(
                "application.conf" to ConfigFactory.parseString("""petshop.db.password = "hunter2-new" """),
                "reference.conf" to ConfigFactory.parseString("""petshop.db.password = "hunter2-old" """),
            )

            val said = layers.origins().report()

            withClue("a report that masks the winner and prints the loser has leaked the secret") {
                said shouldNotContain "hunter2-new"
                said shouldNotContain "hunter2-old"
            }
            said shouldContain "petshop.db.password"
        }

        test("a substitution across layers resolves against the whole document") {
            val layers = layeredConfigOf(
                "application.conf" to ConfigFactory.parseString("""petshop.db.url = "jdbc:"${'$'}{petshop.db.name}"""),
                "reference.conf" to ConfigFactory.parseString("""petshop.db.name = "shop" """),
            )

            layers.config.getString("petshop.db.url") shouldBe "jdbc:shop"
            layers.origins().single { it.path == "petshop.db.url" }.value shouldBe "jdbc:shop"
        }

        test("a default an unset environment variable would override keeps the default and names what it beat") {
            val bus = overridden("SUREFOLD_LAYERED_TEST_UNSET").origins().single { it.path == "petshop.bus" }

            bus.value shouldBe "in-process"
            bus.overrides.shouldNotBeNull { it.value shouldBe "kafka" }
        }

        test("a default a set environment variable overrides takes the variable and names what it beat") {
            System.getenv("PATH").shouldNotBeNull { path ->
                val bus = overridden("PATH").origins().single { it.path == "petshop.bus" }

                bus.value shouldBe path
                bus.overrides.shouldNotBeNull { it.value shouldBe "kafka" }
            }
        }

        test("an override pair in a lower layer is what the winner beat") {
            val layers = layeredConfigOf(
                "system properties" to ConfigFactory.parseString("""petshop.bus = "local" """),
                "application.conf" to overridden("SUREFOLD_LAYERED_TEST_UNSET").layers.first().config,
            )

            val bus = layers.origins().single { it.path == "petshop.bus" }

            bus.value shouldBe "local"
            bus.overrides.shouldNotBeNull { it.value shouldBe "in-process" }
        }

        test("an override pair whose substitution another layer supplies takes that layer's value") {
            val layers = layeredConfigOf(
                "application.conf" to ConfigFactory.parseString(
                    """
                    petshop.bus = "in-process"
                    petshop.bus = ${'$'}{petshop.chosen}
                    """.trimIndent(),
                ),
                "reference.conf" to ConfigFactory.parseString("""petshop { bus = "kafka", chosen = "nats" }"""),
            )

            val bus = layers.origins().single { it.path == "petshop.bus" }

            bus.value shouldBe "nats"
            bus.overrides.shouldNotBeNull { it.value shouldBe "kafka" }
        }

        test("an optional substitution left unset supplies nothing to beat") {
            val layers = layeredConfigOf(
                "application.conf" to ConfigFactory.parseString("petshop.bus = ${'$'}{?SUREFOLD_LAYERED_TEST_UNSET}"),
                "reference.conf" to ConfigFactory.parseString("""petshop.bus = "kafka" """),
            )

            val bus = layers.origins().single { it.path == "petshop.bus" }

            bus.value shouldBe "kafka"
            bus.overrides.shouldBeNull()
        }
    }
}

/**
 * `ConfigFactory.defaultReference()` carries system properties, so a report built on it says
 * reference.conf holds a value that came from `-D`. This is the test that stops that coming back.
 */
object CleanLayersSuite : Suite() {
    init {
        // This suite's one test sets a system property and clears ConfigFactory's caches, both process-wide.
        // Suites run one after another, so no other suite's test sees them while it runs.
        test("the reference layer is the file, not the file with overrides on top") {
            val property = "surefold.layered.test.port"
            System.setProperty(property, "6000")
            try {
                ConfigFactory.invalidateCaches()

                withClue("defaultReference merges system properties in, which is why it is not used") {
                    ConfigFactory.defaultReference().getInt(property) shouldBe 6000
                }
                val layers = layeredConfig().layers

                withClue("a layer named for a file must hold only what that file said") {
                    layers.none { it.name != "system properties" && it.config.hasPath(property) } shouldBe true
                }
                layers.first().name shouldBe "system properties"
            } finally {
                System.clearProperty(property)
                ConfigFactory.invalidateCaches()
            }
        }
    }
}
