package io.github.matthewjones372.surefold.app

import dev.surefold.LogLevel
import dev.surefold.LogLine
import dev.surefold.Logger
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldHaveSize
import dev.surefold.logger
import java.sql.SQLException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private fun failing(message: String) = SQLException(message)

object UncaughtSuite : Suite() {
    init {
        test("what no thread caught is an error in the log, with its stack, not a trace on stderr") {
            val lines = mutableListOf<LogLine>()
            val thrown = failing("the database went away")
            logger.locally(Logger { lines.add(it) }) { Uncaught().uncaughtException(Thread.currentThread(), thrown) }

            lines shouldHaveSize 1
            lines.singleOrNull()?.level shouldBe LogLevel.Error
            lines.singleOrNull()?.message shouldContain "the database went away"
            lines.singleOrNull()?.cause shouldBe thrown
        }

        test(
            "the same failure again within the window is counted, and the count said with the next one after it",
        ) {
            val lines = mutableListOf<LogLine>()
            var now = 0L
            val uncaught = Uncaught(window = 1.minutes) { now }
            logger.locally(Logger { lines.add(it) }) {
                repeat(1_000) { uncaught.uncaughtException(Thread.currentThread(), failing("down")) }
                now += 61.seconds.inWholeNanoseconds
                uncaught.uncaughtException(Thread.currentThread(), failing("down"))
            }

            lines shouldHaveSize 2
            lines.lastOrNull()?.message shouldContain "999 more like it"
        }

        test("a different failure is logged at once, whatever the other did") {
            val lines = mutableListOf<LogLine>()
            val uncaught = Uncaught(window = 1.minutes) { 0L }
            logger.locally(Logger { lines.add(it) }) {
                uncaught.uncaughtException(Thread.currentThread(), failing("down"))
                uncaught.uncaughtException(Thread.currentThread(), IllegalStateException("a bug"))
            }

            lines shouldHaveSize 2
        }
    }
}
