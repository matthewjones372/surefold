package dev.surefold.stream

import dev.surefold.LogLevel
import dev.surefold.LogLine
import dev.surefold.Logger
import dev.surefold.Resource
import dev.surefold.Schedule
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import dev.surefold.junit.withClue
import dev.surefold.logger
import java.util.Collections
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/** A defect starts the same description again, for as long as the schedule says; a declared failure does not. */
object RestartSuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-restart-test")

    private const val GENEROUS_SECONDS = 30L

    private data class Declined(val id: Int)

    private class Recorded : Logger {
        val lines: MutableList<LogLine> = Collections.synchronizedList(mutableListOf())

        override fun log(line: LogLine) {
            lines += line
        }
    }

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS)

    private val quickly = Schedule.spaced<Throwable>(1.milliseconds)

    init {
        test("a stream that dies once is started again, and both runs' elements arrive") { pekko ->
            val thirds = AtomicInteger()

            val exit = Stream.from(listOf(1, 2, 3))
                .map { n -> if (n == 3 && thirds.getAndIncrement() == 0) error("ledger down") else n }
                .restartOnDefect(quickly)
                .runCollect()
                .run(pekko.system)
                .settled()

            withClue("what the first run emitted stays emitted, and the second run starts from the top") {
                exit shouldBe Exit.Done(listOf(1, 2, 1, 2, 3))
            }
        }

        test("a declared failure ends the stream without a restart") { pekko ->
            val runs = AtomicInteger()

            val exit = Stream.from(listOf(1))
                .mapOrFail { n ->
                    runs.incrementAndGet()
                    fail(Declined(n))
                }
                .restartOnDefect(quickly)
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Failed(Declined(1))
            runs.get() shouldBe 1
        }

        test("a schedule that runs out lets the defect through") { pekko ->
            val runs = AtomicInteger()

            val exit = Stream.from(listOf(1))
                .map { _ ->
                    runs.incrementAndGet()
                    error("still down")
                }
                .restartOnDefect(Schedule.recurs(2))
                .runCollect()
                .run(pekko.system)
                .settled()

            exit.shouldBeInstanceOf<Exit.Died> { died -> died.cause.message shouldBe "still down" }
            withClue("the first run, then the two restarts recurs(2) allows") {
                runs.get() shouldBe 3
            }
        }

        test("every restart is a warn line naming the cause, on the logger bound where the stream was built") { pekko ->
            val recorded = Recorded()
            val thirds = AtomicInteger()

            val stream = logger.locally(recorded) {
                Stream.from(listOf(1, 2, 3))
                    .map { n -> if (n == 3 && thirds.getAndIncrement() == 0) error("ledger down") else n }
                    .restartOnDefect(quickly)
            }
            stream.runCollect().run(pekko.system).settled()

            val restart = recorded.lines.single()
            restart.level shouldBe LogLevel.Warn
            restart.message shouldContain "ledger down"
            restart.cause.shouldBeInstanceOf<IllegalStateException>()
            restart.origin shouldBe Restarts::class.java.name
        }
    }
}
