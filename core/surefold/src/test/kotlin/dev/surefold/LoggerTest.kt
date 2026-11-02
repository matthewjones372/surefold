package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldContainExactly
import dev.surefold.assert.shouldHaveSize
import dev.surefold.assert.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant
import java.util.Collections

private class Kennel {
    fun open() = logInfo("open")

    fun eachDog() = listOf("rex").forEach { logInfo("walking $it") }

    fun throughAnObject() = object {
        fun run() = logInfo("anonymous")
    }.run()

    class Gate {
        fun shut() = logInfo("nested")
    }

    companion object {
        fun build() = logInfo("companion")
    }
}

private fun kennelOpens() = logInfo("top level")

class LoggerTest {

    private class Recorded : Logger {
        val lines: MutableList<LogLine> = Collections.synchronizedList(mutableListOf())

        override fun log(line: LogLine) {
            lines += line
        }
    }

    @Test
    fun `a line carries its level and its message`() {
        val recorded = Recorded()

        logger.locally(recorded) {
            logDebug("looking")
            logInfo("found")
            logWarn("slow")
            logError("broken", IllegalStateException("why"))
        }

        recorded.lines.map { it.level } shouldContainExactly
            listOf(LogLevel.Debug, LogLevel.Info, LogLevel.Warn, LogLevel.Error)
        recorded.lines.map { it.message } shouldContainExactly listOf("looking", "found", "slow", "broken")
        recorded.lines.last().cause.shouldNotBeNull().message shouldBe "why"
    }

    @Test
    fun `a line logged inside a parMap branch reaches the bound logger`() {
        val recorded = Recorded()

        logger.locally(recorded) { parMap(listOf(1, 2, 3)) { logInfo("branch $it") } }

        recorded.lines shouldHaveSize 3
        recorded.lines.map { it.message }.toSet() shouldBe setOf("branch 1", "branch 2", "branch 3")
    }

    @Test
    fun `a line is stamped by the clock the thread inherited`() {
        val at = Instant.parse("2026-09-10T00:00:00Z")
        val recorded = Recorded()

        clock.locally(fixedClock(at)) { logger.locally(recorded) { logInfo("stamped") } }

        recorded.lines.single().at shouldBe at
    }

    @Test
    fun `a line names the top-level class it was written from`() {
        val recorded = Recorded()

        logger.locally(recorded) {
            Kennel().open()
            Kennel().eachDog()
            Kennel().throughAnObject()
            Kennel.Gate().shut()
            Kennel.build()
        }

        recorded.lines.map { it.origin }.toSet() shouldBe setOf(Kennel::class.java.name)
        recorded.lines shouldHaveSize 5
    }

    @Test
    fun `a line from a top-level function names its file class`() {
        val recorded = Recorded()

        logger.locally(recorded) { kennelOpens() }

        recorded.lines.single().origin shouldBe "dev.surefold.LoggerTestKt"
    }

    @Test
    fun `stderr prints where a line came from`() {
        val captured = ByteArrayOutputStream()
        val was = System.err

        try {
            System.setErr(PrintStream(captured, true))
            Kennel().open()
        } finally {
            System.setErr(was)
        }

        captured.toString() shouldContain "INFO ${Kennel::class.java.name} open"
    }

    @Test
    fun `nothing bound writes to stderr`() {
        val captured = ByteArrayOutputStream()
        val was = System.err

        try {
            System.setErr(PrintStream(captured, true))
            logInfo("to whoever is listening")
        } finally {
            System.setErr(was)
        }

        captured.toString() shouldContain "to whoever is listening"
        captured.toString() shouldContain "INFO"
    }
}
