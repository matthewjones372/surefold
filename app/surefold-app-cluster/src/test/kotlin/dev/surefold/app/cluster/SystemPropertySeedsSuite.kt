package dev.surefold.app.cluster

import com.typesafe.config.ConfigFactory
import dev.surefold.actor.remote.Node
import dev.surefold.app.typesafe.reading
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldNotBeNull
import java.util.Properties

/** Seeds given as `-Dpath.static.seeds.0=…`, as JAVA_OPTS gives them: an object Typesafe reads as a list. */
object SystemPropertySeedsSuite : Suite() {
    init {
        test("seeds overridden by numbered system properties are a list, in their numbers' order") {
            val properties = Properties().apply {
                setProperty("surefold.cluster.static.seeds.1", "bank-2:25520")
                setProperty("surefold.cluster.static.seeds.0", "bank-1:25520")
                setProperty("surefold.cluster.static.seeds.2", "bank-3:25520")
            }
            val file = ConfigFactory.parseString(
                """
                surefold.cluster { node { name = a, port = 25520 }, join = static, static.seeds = ["127.0.0.1:25520"] }
                """.trimIndent(),
            )
            val section =
                ConfigFactory.parseProperties(properties).withFallback(file).resolve().getConfig("surefold.cluster")

            section.reading { clusterSettings("surefold.cluster") }.getOrNull().shouldNotBeNull { settings ->
                settings.joining().use { joining ->
                    joining.discovery.seeds() shouldContainExactly
                        listOf(Node("", "bank-1", 25520), Node("", "bank-2", 25520), Node("", "bank-3", 25520))
                }
            }
        }
    }
}
