package io.github.matthewjones372.surefold.stream

import dev.surefold.Resource
import dev.surefold.Schedule
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import org.apache.pekko.actor.testkit.typed.javadsl.ManualTime
import org.apache.pekko.stream.testkit.javadsl.TestSink
import scala.jdk.javaapi.CollectionConverters.asJava
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * That a restart on Pekko waits for the delay its schedule decided, on a clock the test moves.
 * `TestStreamsTest` says the same of the test backend; this is the `initialDelay` Pekko's own
 * restart is built on, which nothing else times.
 *
 * A suite of its own because `ManualTime` replaces the system's scheduler, and every timer in any
 * other test on this system would stop with it. Its tests run one at a time for the same reason:
 * a test that moves the clock moves it for every test beside it.
 */
object RestartDelaySuite : SharedSuite<PekkoSystem>(concurrent = false) {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-restart-delay-test", ManualTime.config())

    private val delay = 10.seconds

    private fun clock(pekko: PekkoSystem): ManualTime = ManualTime.get(pekko.typed)

    /** Long enough for an element that was going to arrive to arrive; the scheduled clock does not move. */
    private val quiet = 300.milliseconds.toJavaDuration()

    init {
        test("nothing is emitted again until the schedule's delay has passed") { pekko ->
            val thirds = AtomicInteger()
            val probe = Stream.from(listOf(1, 2, 3))
                .map { n -> if (n == 3 && thirds.getAndIncrement() == 0) error("ledger down") else n }
                .restartOnDefect(Schedule.spaced(delay))
                .toSource()
                .runWith(TestSink.probe(pekko.classic), pekko.system)

            probe.request(10)
            // The probe's expectNext throws; expectNextN hands back a Scala Seq, seen as a Java list it can equal.
            asJava(probe.expectNextN(2)) shouldBe listOf(1, 2)
            probe.expectNoMessage(quiet)

            clock(pekko).timePasses((delay - 1.seconds).toJavaDuration())
            probe.expectNoMessage(quiet)

            clock(pekko).timePasses(1.seconds.toJavaDuration())
            asJava(probe.expectNextN(3)) shouldBe listOf(1, 2, 3)
            probe.expectComplete()
        }
    }
}
