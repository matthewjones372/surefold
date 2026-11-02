package dev.surefold.slf4j

import dev.surefold.Logger
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.withClue
import dev.surefold.logger
import java.util.ServiceLoader

/**
 * The whole of the wiring: this jar is on the classpath, so this is where a line goes.
 *
 * The service file is what a consumer never reads and every consumer depends on, so it is asserted
 * rather than assumed — a typo in it is a silent return to stderr.
 */
object OnTheClasspathSuite : Suite() {
    init {
        test("nothing is bound, and a line still goes to the backend") {
            withClue("what main reads with no logger.locally above it, which is the point of the module") {
                logger.unbound().shouldBeInstanceOf<Slf4jLogger>()
            }
        }

        test("the service file names a class that exists and takes no arguments") {
            val found = ServiceLoader.load(Logger::class.java, Logger::class.java.classLoader).toList()

            withClue("ServiceLoader needs a no-argument constructor; every parameter having a default is") {
                found.map { it::class } shouldBe listOf(Slf4jLogger::class)
            }
        }
    }
}
