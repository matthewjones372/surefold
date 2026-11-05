package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContain
import dev.surefold.assert.withClue
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.ServiceConfigurationError
import java.util.ServiceLoader
import org.junit.jupiter.api.Test

/** Where a line goes when nobody has bound anything: whatever the classpath registered, or stderr. */
class DiscoveredLoggerTest {

    @Test
    fun `nothing registered leaves stderr, which is enough to watch a service start`() {
        withClue("this module registers no Logger of its own, so this is the fallback under test") {
            ServiceLoader.load(Logger::class.java, Logger::class.java.classLoader).count() shouldBe 0
        }
        logger.get() shouldBe StderrLogger
    }

    @Test
    fun `a registered logger that cannot be loaded leaves stderr, and says so once`() {
        val said = ByteArrayOutputStream()
        val err = System.err

        val fallen =
            try {
                System.setErr(PrintStream(said, true))
                firstRegistered("Logger", StderrLogger) {
                    throw ServiceConfigurationError("petshop.LoggerThatIsNotThere")
                }
            } finally {
                System.setErr(err)
            }

        withClue("a service file naming a class that will not link must not take the first line out") {
            fallen shouldBe StderrLogger
        }
        withClue("silence here is a service logging to stderr with no idea why") {
            said.toString() shouldContain "petshop.LoggerThatIsNotThere"
        }
    }

    @Test
    fun `a binding still wins over whatever the classpath had`() {
        val mine = Logger {}

        logger.locally(mine) { logger.get() } shouldBe mine
    }

    @Test
    fun `unbound answers what the classpath had, under a binding of another`() {
        logger.locally(Logger {}) { logger.unbound() } shouldBe StderrLogger
    }
}
