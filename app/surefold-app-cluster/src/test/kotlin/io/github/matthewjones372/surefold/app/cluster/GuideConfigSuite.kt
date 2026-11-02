package io.github.matthewjones372.surefold.app.cluster

import com.typesafe.config.ConfigFactory
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.withClue
import io.github.matthewjones372.surefold.app.typesafe.reading
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/** Hands the guide's fence to [then], once the build has named the repo and the page holds the fence. */
private fun fence(then: (String) -> Unit) {
    val root = System.getProperty("surefold.app.cluster.repoRoot")
    return withClue("the build must pass -Dsurefold.app.cluster.repoRoot; see surefold-app-cluster/build.gradle.kts") {
        root.shouldNotBeNull { repo ->
            val page = File(repo, "docs/cluster.md").readText()
            val found =
                Regex("""<!-- cluster-joined -->\s*```hocon\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL).find(page)
            withClue("docs/cluster.md must hold a hocon fence marked <!-- cluster-joined -->") {
                found.shouldNotBeNull { then(it.groupValues[1]) }
            }
        }
    }
}

/** The section `docs/cluster.md` shows, read as `cluster(path)` reads it (spec 0096). */
object GuideConfigSuite : Suite() {
    init {
        test("the guide's section reads without a fault, and joins through its static seeds") {
            fence { text ->
                val section = ConfigFactory.parseString(text).resolve().getConfig("shop.cluster")
                val read = section.reading { clusterSettings("shop.cluster") }
                withClue(read.leftOrNull()) {
                    read.getOrNull().shouldNotBeNull { settings ->
                        settings.name shouldBe "shop-1"
                        settings.port shouldBe 25520
                        settings.gossiping.ackWithin shouldBe 600.milliseconds
                        settings.whenDowned shouldBe WhenDowned.Exit
                        settings.joining().use {
                            it.discovery.seeds().map { seed -> seed.port } shouldContainExactly listOf(25520)
                        }
                    }
                }
            }
        }
    }
}
