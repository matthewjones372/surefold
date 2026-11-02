package io.github.matthewjones372.surefold.stream

import dev.surefold.LogLevel
import dev.surefold.capturingLogs
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeLessThan
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldHaveSize
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Spec 0130: a close waits for a read in flight, but not for ever. */
object OpenedSuite : Suite() {
    /** A source whose `next` blocks until [release], and whose `wake` reaches nothing. */
    private class Deaf {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean(false)

        fun node() = Node.Blocking(
            open = { this },
            next = {
                reading.countDown()
                release.await()
                null
            },
            wake = { },
            close = { closed.set(true) },
            at = "OpenedTest",
        )
    }

    init {
        test("a read its wake does not end is closed under once the wait runs out, with a warning") {
            val deaf = Deaf()
            val opened = Opened(deaf.node(), readEndsWithin = 200.milliseconds)
            val reader = Thread.ofVirtual().start { opened.next() }
            val _ = deaf.reading.await(1, TimeUnit.MINUTES)
            try {
                capturingLogs { logs ->
                    opened.close()

                    val warned = logs.all().filter { it.level == LogLevel.Warn }
                    deaf.closed.get() shouldBe true
                    warned shouldHaveSize 1
                    warned.singleOrNull()?.message shouldContain "closed while still reading"
                }
            } finally {
                deaf.release.countDown()
                reader.join()
            }
        }

        test("a close interrupted while it waits closes at once and keeps the interrupt") {
            val deaf = Deaf()
            val opened = Opened(deaf.node(), readEndsWithin = 1.minutes)
            val reader = Thread.ofVirtual().start { opened.next() }
            val _ = deaf.reading.await(1, TimeUnit.MINUTES)
            try {
                val started = System.nanoTime()
                Thread.currentThread().interrupt()
                opened.close()

                Thread.interrupted() shouldBe true
                deaf.closed.get() shouldBe true
                TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) shouldBeLessThan 10L
            } finally {
                deaf.release.countDown()
                reader.join()
            }
        }
    }
}
