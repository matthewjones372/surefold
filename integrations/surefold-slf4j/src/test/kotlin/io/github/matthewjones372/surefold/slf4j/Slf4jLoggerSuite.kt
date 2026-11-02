package io.github.matthewjones372.surefold.slf4j

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.surefold.LogLevel
import dev.surefold.LogLine
import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeNull
import dev.surefold.junit.withClue
import dev.surefold.logAnnotated
import dev.surefold.logDebug
import dev.surefold.logError
import dev.surefold.logInfo
import dev.surefold.logWarn
import dev.surefold.logger
import dev.surefold.parMap
import dev.surefold.resource
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Instant

private fun <A> logging(block: () -> A): A = logger.locally(Slf4jLogger(), block)

private fun <A> LoggerContext.capturing(name: String, block: () -> A): List<ILoggingEvent> {
    val named = getLogger(name)
    val its = ListAppender<ILoggingEvent>().also { it.start() }
    named.addAppender(its)
    try {
        val _ = block()
    } finally {
        named.detachAppender(its)
    }
    return its.list
}

/** What reached the logger named after this suite, the name the adapter logs a test's lines under, in [block]. */
private fun <A> LoggerContext.appended(block: () -> A): List<ILoggingEvent> =
    capturing(Slf4jLoggerSuite::class.java.name, block)

/**
 * What a line looks like once it reaches a backend, asserted against logback rather than a fake.
 *
 * The appender is attached to the name the adapter logs under, this suite's own, because a service
 * configures a backend by name and a test that bypassed the name would not notice the adapter dropping it.
 */
// Not concurrent: seven of the ten tests read what reached logback's logger named after this suite, and an
// appender on it sees every test's lines. That logger is the adapter's, so the tests cannot each have their own.
object Slf4jLoggerSuite : SharedSuite<LoggerContext>(concurrent = false) {
    // Asked for once, before any test runs: SLF4J answers a call made while another thread is still
    // initialising it with a stand-in factory, which is not logback's.
    override val shared: Resource<LoggerContext> = resource { LoggerFactory.getILoggerFactory() as LoggerContext }

    init {
        test("each level reaches the call the backend names it by") { backend ->
            val lines = backend.appended {
                logging {
                    logDebug("debug")
                    logInfo("info")
                    logWarn("warn")
                    logError("error")
                }
            }

            lines.map { it.level to it.message } shouldBe listOf(
                Level.DEBUG to "debug",
                Level.INFO to "info",
                Level.WARN to "warn",
                Level.ERROR to "error",
            )
        }

        test("a message arrives as it was written, with nothing appended to it") { backend ->
            val lines = backend.appended { logging { logInfo("one tortoise, adopted") } }

            lines.single().message shouldBe "one tortoise, adopted"
        }

        test("a cause is a throwable to the backend, not a line of text") { backend ->
            val boom = IllegalStateException("no tortoises")

            val lines = backend.appended { logging { logError("adoption failed", boom) } }

            withClue("a stack trace rendered into the message is one no backend can group by") {
                lines.single().throwableProxy.message shouldBe "no tortoises"
            }
        }

        test("a line reaches the logger named after the class that wrote it") { backend ->
            val lines =
                backend.capturing(Registry::class.java.name) { logging { Registry().register("tortoise-1") } }

            withClue("a backend configured by package reads this name, not `surefold`") {
                lines.map { it.loggerName to it.message } shouldBe listOf(
                    Registry::class.java.name to "registering tortoise-1",
                )
            }
        }

        test("the name a service configures is the name it was given") { backend ->
            var lines = emptyList<ILoggingEvent>()
            val suites = backend.appended {
                lines = backend.capturing("app") { logger.locally(Slf4jLogger("app")) { logInfo("named") } }
            }

            lines.map { it.loggerName to it.message } shouldBe listOf("app" to "named")
            suites.shouldBe(emptyList())
        }

        test("a line with no origin reaches surefold") { backend ->
            val lines = backend.capturing("surefold") {
                Slf4jLogger().log(LogLine(LogLevel.Info, "unplaced", Instant.EPOCH))
            }

            lines.map { it.message } shouldBe listOf("unplaced")
        }

        test("an annotation is in the MDC while its block runs, and gone afterwards") { backend ->
            val (inside, outside) = backend.appended {
                logging {
                    logAnnotated("pet_id" to "tortoise-1") { logInfo("inside") }
                    logInfo("outside")
                }
            }

            withClue("a pattern of %X{pet_id} and a field search both read this and not the message") {
                inside.mdcPropertyMap["pet_id"] shouldBe "tortoise-1"
            }
            withClue("a pooled thread hands itself to something else next, and must carry nothing over") {
                outside.mdcPropertyMap["pet_id"].shouldBeNull()
            }
        }

        test("a line written on a fork carries what its opener bound") { backend ->
            val lines = backend.appended {
                logging {
                    logAnnotated("pet_id" to "tortoise-1") {
                        val _ = parMap(listOf(1, 2)) { logInfo("fork $it") }
                    }
                }
            }

            withClue("the claim an MDC cannot make by itself, and the reason this adapter exists") {
                lines.map { it.mdcPropertyMap["pet_id"] } shouldBe listOf("tortoise-1", "tortoise-1")
            }
        }

        test("a key the service set outside surefold is still there afterwards") { backend ->
            MDC.put("service", "petshop")

            val lines = backend.appended { logging { logAnnotated("pet_id" to "tortoise-1") { logInfo("both") } } }

            withClue("put back rather than cleared: what surefold did not set is not surefold's to drop") {
                MDC.get("service") shouldBe "petshop"
            }
            lines.single().mdcPropertyMap["service"] shouldBe "petshop"
            MDC.clear()
        }

        test("every level surefold has is one this knows") { _ ->
            withClue("a `when` over LogLevel has no else, so a new level is a compiler error here") {
                LogLevel.entries.size shouldBe 4
            }
        }
    }
}

private class Registry {
    fun register(id: String) = logInfo("registering $id")
}
