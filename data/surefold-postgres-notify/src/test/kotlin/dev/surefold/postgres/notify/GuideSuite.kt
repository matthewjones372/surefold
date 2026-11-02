package dev.surefold.postgres.notify

import dev.surefold.actor.journal.jdbc.Postgres
import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBe
import dev.surefold.test.eventually
import io.github.matthewjones372.surefold.stream.Exit
import io.github.matthewjones372.surefold.stream.Forks
import io.github.matthewjones372.surefold.stream.Hub
import io.github.matthewjones372.surefold.stream.runCollect
import io.github.matthewjones372.surefold.stream.start
import io.github.matthewjones372.surefold.stream.take
import org.postgresql.ds.PGSimpleDataSource
import java.io.File
import kotlin.time.Duration.Companion.seconds

/** docs/postgres-notify.md's example is this module's GuideExample.kt, and a booking reaches a page through it. */
object GuideSuite : Suite() {
    private val marked = Regex("""<!-- postgres-notify -->\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)

    init {
        test("the guide's example is GuideExample.kt from its imports down") {
            val root = System.getProperty("surefold.postgres.notify.repoRoot")
            expect(root != null) { "the build must pass -Dsurefold.postgres.notify.repoRoot; see its build file" }
            val fences = marked.findAll(File(root!!, "docs/postgres-notify.md").readText()).map { it.groupValues[1] }
            val source = File(root, EXAMPLE).readText()
            fences.toList().map { it.trim() } shouldBe listOf(source.substring(source.indexOf("import ")).trim())
        }

        test("a committed booking reaches a page subscribed to the hub") {
            val source = PGSimpleDataSource().apply { setUrl(Postgres.emptyUrl()) }
            Hub<LiveEvent>().use { hub ->
                val page = hub.subscribe().take(1).runCollect().start(Forks())
                liveEvents(source, hub).use { _ ->
                    // Sent until the listener hears one: what is sent before it listens is gone.
                    eventually(within = 10.seconds) {
                        source.connection.use { booked(it, "b-1") }
                        page.exit.toCompletableFuture().isDone shouldBe true
                    }
                }
                page.exit.toCompletableFuture().join() shouldBe Exit.Done(listOf(LiveEvent.Booked("b-1")))
            }
        }
    }

    private const val EXAMPLE =
        "data/surefold-postgres-notify/src/test/kotlin/" +
            "dev/surefold/postgres/notify/GuideExample.kt"
}
