package dev.surefold.jobs

import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private object Charge : JobKind<String>("charge", Notes, rate = JobRate(10, per = 1.seconds))

/** A kind's rate, shared by every worker through its bucket row (spec 0217). */
object RateSuite : Suite() {
    private val noon = Instant.parse("2026-10-26T12:00:00Z")

    init {
        test("two workers draining a kind limited to 10 a second, with a burst of 10, claim 10 plus 10 a second") {
            val source = jobsDatabase()
            val time = TestClock(noon)
            clock.locally(time) {
                source.committed { connection -> (1..100).forEach { Jobs.enqueue(connection, Charge, "$it", "$it") } }
                source.claimedByTwo(time) shouldBe 10
                source.claimedByTwo(time) shouldBe 0
                time.adjust(1.seconds)
                source.claimedByTwo(time) shouldBe 10
                time.adjust(500.milliseconds)
                source.claimedByTwo(time) shouldBe 5
            }
        }

        test("an idle bucket refills to its burst and no further") {
            val source = jobsDatabase()
            val time = TestClock(noon)
            clock.locally(time) {
                source.committed { connection -> (1..100).forEach { Jobs.enqueue(connection, Charge, "$it", "$it") } }
                val queue = Queue(source, "a")
                queue.claim(100, 1.minutes, Kinds.Only(Charge)).size shouldBe 10
                time.adjust(1.hours)
                queue.claim(100, 1.minutes, Kinds.Only(Charge)).size shouldBe 10
            }
        }

        test("a kind with no rate is claimed as before") {
            val source = jobsDatabase()
            clock.locally(TestClock(noon)) {
                source.committed { connection -> (1..30).forEach { Jobs.enqueue(connection, Remind, "$it", "$it") } }
                Queue(source, "a").claim(100, 1.minutes, Kinds.Only(Remind)).size shouldBe 30
            }
        }

        test("a rate needs at least one run per a positive time") {
            shouldThrow<IllegalArgumentException> { JobRate(0, per = 1.seconds) }
        }
    }

    /** Two workers claiming at the same instant, each as much as it can; answers how many they took between them. */
    private fun DataSource.claimedByTwo(time: TestClock): Int {
        val total = AtomicInteger()
        val ready = CountDownLatch(2)
        listOf("a", "b").map { name ->
            Thread.ofVirtual().start {
                clock.locally(time) {
                    ready.countDown()
                    ready.await()
                    total.addAndGet(Queue(this, name).claim(100, 1.minutes, Kinds.Only(Charge)).size)
                }
            }
        }.forEach { it.join() }
        return total.get()
    }
}
