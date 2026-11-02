package io.github.matthewjones372.surefold.app.jobs

import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.actor.journal.jdbc.Postgres
import io.github.matthewjones372.surefold.app.overriding
import io.github.matthewjones372.surefold.app.singleOf
import io.github.matthewjones372.surefold.app.testApp
import io.github.matthewjones372.surefold.jobs.JobSettings
import io.github.matthewjones372.surefold.jobs.JobWorker
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** docs/jobs.md's example is this module's GuideExample.kt, and it runs. */
object JobsGuideSuite : Suite() {
    private val marked = Regex("""<!-- jobs -->\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)

    init {
        test("the guide's example is GuideExample.kt from its imports down") {
            val root = System.getProperty("surefold.app.jobs.repoRoot")
            expect(root != null) { "the build must pass -Dsurefold.app.jobs.repoRoot; see its build.gradle.kts" }
            val fences = marked.findAll(File(root!!, "docs/jobs.md").readText()).map { it.groupValues[1] }.toList()
            fences.size shouldBe 1
            val source = File(root, EXAMPLE).readText()
            source.substring(source.indexOf("import ")).trim() shouldBe fences.single().trim()
        }

        test("the guide's example enqueues a reminder that the worker sends once it is due") {
            val sent = ConcurrentLinkedQueue<String>()
            val arrived = CountDownLatch(1)
            val source = Postgres.empty()
            val time = TestClock(Instant.now())
            val module = (
                singleOf<DataSource> { source } +
                    reminders { orderId -> sent.add(orderId).also { arrived.countDown() } }
                )
                .overriding(singleOf { -> JobSettings(poll = 20.milliseconds) })
            clock.locally(time) {
                testApp(module) { _: JobWorker ->
                    source.connection.use { connection -> remindLater(connection, "o-1") }
                    time.adjust(2.seconds)
                    arrived.await(1, TimeUnit.SECONDS) shouldBe false
                    time.adjust(3_600.seconds)
                    arrived.await(30, TimeUnit.SECONDS) shouldBe true
                }
            }
            sent.toList() shouldBe listOf("o-1")
        }
    }

    private const val EXAMPLE =
        "data/surefold-app-jobs/src/test/kotlin/io/github/matthewjones372/surefold/app/jobs/GuideExample.kt"
}
