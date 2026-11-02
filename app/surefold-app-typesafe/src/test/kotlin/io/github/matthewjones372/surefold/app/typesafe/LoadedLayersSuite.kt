package io.github.matthewjones372.surefold.app.typesafe

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import dev.surefold.junit.Suite
import dev.surefold.junit.alone
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.withClue
import io.github.matthewjones372.surefold.app.testApp
import java.nio.file.Files

/**
 * `layeredConfig()` is `ConfigFactory.load()` kept apart, so the two agree on the merge in every case
 * `load()` reads differently: a replacement file, the environment forced on top, and the other syntaxes.
 */
object LoadedLayersSuite : Suite() {
    /** Runs [body] with a way to set system properties, each cleared again once it returns. */
    private fun withProperties(body: (property: (name: String, value: String) -> Unit) -> Unit) {
        val set = mutableListOf<String>()
        try {
            return body { name, value ->
                System.setProperty(name, value)
                set += name
                ConfigFactory.invalidateCaches()
            }
        } finally {
            set.forEach(System::clearProperty)
            ConfigFactory.invalidateCaches()
        }
    }

    private fun Layered.names() = layers.map { it.name }

    init {
        test("with nothing set, the merge is what load reads") {
            val layered = layeredConfig()

            layered.config shouldBe ConfigFactory.load()
            layered.names().shouldContainExactly(
                "system properties",
                "application.conf",
                "application.json",
                "application.properties",
                "reference.conf",
            )
        }

        test("application properties are read below application conf") {
            val layered = layeredConfig()

            val holder = layered.layers.single { it.config.hasPath("surefold.layered.properties") }
            layered.config.getString("surefold.layered.properties") shouldBe "from-properties"
            holder.name shouldBe "application.properties"
        }

        // Alone, as are the two after it: it sets system properties and clears ConfigFactory's caches, both
        // process-wide, and a test reading ConfigFactory.load() or layeredConfig() beside it sees them.
        test("config file replaces the application files", alone) {
            withProperties { property ->
                val file = Files.createTempFile("surefold-layered", ".conf")
                try {
                    Files.writeString(file, "surefold.layered.file = from-file")
                    property("config.file", file.toString())

                    val layered = layeredConfig()

                    layered.config shouldBe ConfigFactory.load()
                    layered.config.getString("surefold.layered.file") shouldBe "from-file"
                    withClue("config.file replaces application.*, it does not sit beside them") {
                        layered.config.hasPath("surefold.layered.properties") shouldBe false
                    }
                    layered.names().shouldContainExactly("system properties", file.toString(), "reference.conf")
                } finally {
                    Files.delete(file)
                }
            }
        }

        test("CONFIG_FORCE_ variables sit above system properties once asked for", alone) {
            withProperties { property ->
                property("surefold.layered.forced", "from-property")
                property("config.override_with_env_vars", "true")

                val layered = layeredConfig()

                layered.config shouldBe ConfigFactory.load()
                layered.config.getString("surefold.layered.forced") shouldBe "from-env"
                layered.names().take(2) shouldContainExactly listOf("environment overrides", "system properties")
            }
        }

        test("CONFIG_FORCE_ variables are not read unless asked for", alone) {
            withProperties { property ->
                property("surefold.layered.forced", "from-property")

                layeredConfig().config.getString("surefold.layered.forced") shouldBe "from-property"
            }
        }

        test("loadedConfig provides the layers and their merge") {
            testApp(loadedConfig()) { layered: Layered -> layered.names().last() } shouldBe "reference.conf"
            testApp(loadedConfig()) { config: Config -> config } shouldBe ConfigFactory.load()
        }

        test("a document written in the test is one layer") {
            val layered = testApp(configOf("a = 1")) { layered: Layered -> layered }

            layered.layers.size shouldBe 1
            layered.config.getInt("a") shouldBe 1
        }

        test("overridingConfig puts its document on top of the layers") {
            val base = layeredConfigOf("base" to ConfigFactory.parseString("a = 1, b = 2"))
            val app = configOf("a = 1, b = 2").overridingConfig("a = 3", fallback = base)

            testApp(app) { layered: Layered -> layered.names() } shouldContainExactly listOf("overrides", "base")
            testApp(app) { config: Config -> config.getInt("a") to config.getInt("b") } shouldBe (3 to 2)
        }
    }
}
