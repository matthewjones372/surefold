package io.github.matthewjones372.surefold.app.typesafe

import com.typesafe.config.ConfigFactory
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldNotContain
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withClue
import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.StartupError
import io.github.matthewjones372.surefold.app.render
import io.github.matthewjones372.surefold.app.single
import io.github.matthewjones372.surefold.app.testApp
import io.github.matthewjones372.surefold.app.use
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import com.typesafe.config.Config as Hocon

private interface Repo

private class Plain : Repo

private class Caching(val ttl: Duration) : Repo

private data class RepoConf(val useCache: Boolean, val ttl: Duration)

private class Orders(val repo: Repo)

/** A setting that does not configure a repository but chooses between two of them. */
object ChoicesSuite : Suite() {
    private fun repo(): Reading.() -> RepoConf = { RepoConf(boolean("useCache"), duration("ttl")) }

    private fun persistence(conf: Hocon, caching: Module, plain: Module): Module =
        conf.choosing("repo", repo()) { repo -> if (repo.useCache) caching else plain }

    private fun caching(): Module = single { conf: RepoConf -> Caching(conf.ttl) as Repo }

    private fun plain(): Module = single<Repo> { Plain() }

    private fun watched(built: AtomicBoolean): Module = single<Repo> {
        built.set(true)
        Plain()
    }

    private fun hocon(useCache: Boolean): Hocon =
        ConfigFactory.parseString("repo { useCache = $useCache, ttl = 5s }").resolve()

    init {
        test("the setting picks which module the graph holds") {
            val cached = hocon(useCache = true)

            val app = configOf(cached) + persistence(cached, caching(), plain())

            testApp(app) { repo: Repo -> repo }.shouldBeInstanceOf<Caching> { it.ttl shouldBe 5.seconds }
        }

        test("the module the setting did not pick is not in the graph at all") {
            val built = AtomicBoolean(false)
            val uncached = hocon(useCache = false)

            val app = configOf(uncached) + persistence(uncached, watched(built), plain())

            testApp(app) { repo: Repo -> repo }.shouldBeInstanceOf<Plain>()
            withClue(
                "a node surefold-app holds is a node surefold-app builds, so the unchosen one must be absent",
            ) {
                built.get() shouldBe false
                app.render() shouldNotContain "Caching"
            }
        }

        test("the section the choice read is a node, so nothing reads the path twice") {
            val cached = hocon(useCache = true)

            val app = configOf(cached) + persistence(cached, caching(), plain()) +
                single { repo: Repo -> Orders(repo) }

            testApp(app) { orders: Orders -> orders.repo }.shouldBeInstanceOf<Caching> { it.ttl shouldBe 5.seconds }
        }

        test("a section that will not read refuses the start, naming every fault") {
            val empty = ConfigFactory.parseString("repo {}").resolve()

            val app = configOf(empty) + persistence(empty, caching(), plain())

            app.use { _: Repo -> }.leftOrNull().shouldBeInstanceOf<StartupError.Refused> { error ->
                withClue("one message listing what is wrong beats one deploy per fault") {
                    error.reason shouldContain "useCache"
                    error.reason shouldContain "ttl"
                }
            }
        }

        test("a read that throws on a discarded value names the faults where the choice is written") {
            val empty = ConfigFactory.parseString("repo {}").resolve()

            shouldThrow<IllegalStateException>({
                empty.choosing("repo", { require(boolean("useCache")) { "useCache" } }) { plain() }
            }) { thrown ->
                withClue("the fault is the thing to fix, not the require it tripped on the way past") {
                    thrown.message.orEmpty() shouldContain "useCache"
                }
            }
        }

        test("a read that throws with no fault recorded throws its own exception, not an empty refusal") {
            val cached = hocon(useCache = true)

            val off: Reading.() -> Unit = { require(!boolean("useCache")) { "caching is switched off here" } }
            shouldThrow<IllegalArgumentException>({ cached.choosing("repo", off) { plain() } }) { thrown ->
                thrown.message shouldBe "caching is switched off here"
            }
        }

        test("the document the assembly read is the one the graph holds") {
            val cached = hocon(useCache = true)

            testApp(configOf(cached)) { held: Hocon -> held } shouldBe cached
        }
    }
}
