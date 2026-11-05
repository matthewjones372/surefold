package dev.surefold

import dev.surefold.assert.shouldContain
import dev.surefold.assert.withClue
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit

/** A test that times out leaves every thread's stack in its report, virtual threads among them. */
class ThreadsOnTimeoutTest {

    @Test
    fun `a timed-out test prints the stack of a virtual thread it left parked`() {
        val printed = ByteArrayOutputStream()
        val err = System.err
        System.setProperty(FIXTURE, "true")
        System.setErr(PrintStream(printed, true))
        try {
            EngineTestKit.engine("junit-jupiter")
                .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
                .selectors(selectClass(TimesOut::class.java))
                .execute()
                .testEvents()
                .assertStatistics { it.failed(1) }
        } finally {
            System.setErr(err)
            System.clearProperty(FIXTURE)
        }

        withClue("the dump names the parked virtual thread and the frame it is parked in") {
            printed.toString() shouldContain "\"$PARKED\" virtual"
            printed.toString() shouldContain "TimesOut.parkedForTheDump"
        }
    }

    /** Run only by the test above: on its own it would time out, which is its job. */
    @EnabledIfSystemProperty(named = FIXTURE, matches = "true")
    class TimesOut {

        @Test
        @Timeout(1, unit = TimeUnit.SECONDS)
        fun `parks a virtual thread and outlives its timeout`() {
            val parked = Thread.ofVirtual().name(PARKED).start(::parkedForTheDump)
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(OUTLIVES_SECONDS))
            } finally {
                LockSupport.unpark(parked)
                parked.join()
            }
        }

        private fun parkedForTheDump() = LockSupport.park()
    }

    private companion object {
        const val FIXTURE = "surefold.threadsOnTimeoutFixture"
        const val PARKED = "parked-for-the-dump"
        const val OUTLIVES_SECONDS = 30L
    }
}
