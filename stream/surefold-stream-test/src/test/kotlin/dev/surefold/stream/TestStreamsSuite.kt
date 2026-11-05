package dev.surefold.stream

import dev.surefold.LogLevel
import dev.surefold.LogLine
import dev.surefold.Logger
import dev.surefold.Schedule
import dev.surefold.TestClock
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import dev.surefold.junit.withClue
import dev.surefold.logger
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/** A run on TestStreams is over, or waiting on the test's clock, by the time `start` returns. */
object TestStreamsSuite : Suite() {
    private data class Odd(val value: Int)

    private val <E, R> Running<E, R>.done: Exit<E, R>?
        get() = exit.toCompletableFuture().takeIf { it.isDone }?.join()

    init {
        test("a run with no time in it is over when run returns, having run one stage at a time") {
            val threads = ConcurrentLinkedQueue<Thread>()

            val exit =
                Stream.of(1, 2, 3)
                    .map {
                        threads += Thread.currentThread()
                        it * 2
                    }
                    .runCollect()
                    .run(TestStreams())

            exit.toCompletableFuture().isDone shouldBe true
            exit.toCompletableFuture().join() shouldBe Exit.Done(listOf(2, 4, 6))
            threads.toSet().size shouldBe 1
        }

        test("a declared failure ends the run Failed, as on any backend") {
            val exit =
                Stream.of(1, 2, 3).mapOrFail { if (it == 2) fail(Odd(it)) else it }.runCollect().run(TestStreams())

            exit.toCompletableFuture().join() shouldBe Exit.Failed(Odd(2))
        }

        test("another backend's own value is refused by its own name") {
            val elsewhere =
                Stream<Nothing, Int>(Node.Native(Any(), BackendKey("Elsewhere"), "Elsewhere.source", "Here.kt:1"))

            val exit = Stream.of(1, 2).merge(elsewhere).runCollect().run(TestStreams())

            exit.toCompletableFuture().join().shouldBeInstanceOf<Exit.Died> { died ->
                died.cause.message shouldContain "and this run was started on TestStreams"
            }
        }

        test("merge on a test's clock answers in the same order every time, the first stream then the other") {
            (1..20).forAll { _ ->
                Stream.of(1, 2)
                    .merge(Stream.of(3))
                    .runCollect()
                    .run(TestStreams())
                    .toCompletableFuture()
                    .join() shouldBe Exit.Done(listOf(1, 2, 3))
            }
        }

        test("mapPar runs one element at a time in the order they came, and its raise is the stream's") {
            val inFlight = java.util.concurrent.atomic.AtomicInteger()
            val most = java.util.concurrent.atomic.AtomicInteger()

            val running =
                Stream.from(1..5)
                    .mapPar<Odd, Int, Int>(4) { n ->
                        most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                        inFlight.decrementAndGet()
                        if (n == 4) fail(Odd(n)) else n * 10
                    }
                    .runCollect()
                    .start(TestStreams())

            running.done shouldBe Exit.Failed(Odd(4))
            running.emitted() shouldBe listOf(10, 20, 30)
            most.get() shouldBe 1
        }

        test("an hour of ticks at one a minute is sixty elements, in well under a second") {
            val clock = TestClock()
            val took = measureTime {
                val running = Stream.tick(1.minutes, "t").runCollect().start(TestStreams(clock))

                running.emitted().shouldBeEmpty()
                clock.adjust(1.hours)

                running.emitted().size shouldBe 60
                running.close()
                running.done shouldBe Exit.Done(List(60) { "t" })
            }
            withClue("took $took") { (took < 1.seconds) shouldBe true }
        }

        test("each move of the clock returns with what fell due by then, and a take ends the run") {
            val clock = TestClock()
            val running =
                Stream.tick(10.seconds, 1, after = 5.seconds)
                    .scan(0) { total, n -> total + n }
                    .take(4)
                    .runCollect()
                    .start(TestStreams(clock))

            running.emitted() shouldBe listOf(0)
            clock.adjust(4.seconds)
            running.emitted() shouldBe listOf(0)
            clock.adjust(1.seconds)
            running.emitted() shouldBe listOf(0, 1)
            running.done shouldBe null
            clock.adjust(20.seconds)
            running.done shouldBe Exit.Done(listOf(0, 1, 2, 3))
        }

        test("a stage body reads the test's clock as surefold's clock") {
            val clock = TestClock(Instant.parse("2026-09-25T12:00:00Z"))
            val running =
                Stream.tick(1.minutes, Unit)
                    .map { dev.surefold.clock.get().now() }
                    .take(2)
                    .runCollect()
                    .start(TestStreams(clock))

            clock.adjust(2.minutes)

            running.done shouldBe
                Exit.Done(
                    listOf(Instant.parse("2026-09-25T12:01:00Z"), Instant.parse("2026-09-25T12:02:00Z")),
                )
        }

        test("groupedWithin closes a window when its time is up, and a full group starts the next") {
            val clock = TestClock()
            val running =
                Stream.tick(2.seconds, 1)
                    .groupedWithin(3, 5.seconds)
                    .map { it.size }
                    .runCollect()
                    .start(TestStreams(clock))

            clock.adjust(5.seconds)
            withClue("the window closing at 5s held the ticks at 2s and 4s") {
                running.emitted() shouldBe listOf(2)
            }
            clock.adjust(4.seconds)
            running.emitted() shouldBe listOf(2)
            clock.adjust(1.seconds)
            withClue("the tick at 10s arrives before the window closing at 10s does, and fills the group") {
                running.emitted() shouldBe listOf(2, 3)
            }
            clock.adjust(5.seconds)
            withClue("so the next window runs from 10s to 15s, and holds 12s and 14s") {
                running.emitted() shouldBe listOf(2, 3, 2)
            }
            running.close()
        }

        test("groupedWithin emits what a window held when it closes before the group is full") {
            val clock = TestClock()
            val running =
                Stream.tick(2.seconds, 1).take(3).groupedWithin(10, 5.seconds).runCollect().start(TestStreams(clock))

            clock.adjust(5.seconds)
            withClue("the ticks at 2s and 4s, when the window closes at 5s") {
                running.emitted() shouldBe listOf(listOf(1, 1))
            }
            clock.adjust(1.seconds)
            withClue("the tick at 6s is the last, and the end emits what the next window held") {
                running.done shouldBe Exit.Done(listOf(listOf(1, 1), listOf(1)))
            }
        }

        test("a restart waits the schedule's delay on the test's clock, and says so at the clock's time") {
            val clock = TestClock()
            val lines = ConcurrentLinkedQueue<LogLine>()
            val attempts = ConcurrentLinkedQueue<Int>()
            val flaky =
                logger.locally(Logger { lines += it }) {
                    Stream.of(1, 2)
                        .map { n ->
                            attempts += n
                            check(!(n == 2 && attempts.size < 6)) { "not yet" }
                            n
                        }
                        .restartOnDefect(Schedule.spaced(30.seconds))
                        .runCollect()
                }

            val running = flaky.start(TestStreams(clock))

            running.emitted() shouldBe listOf(1)
            clock.adjust(29.seconds)
            running.emitted() shouldBe listOf(1)
            clock.adjust(1.seconds)
            running.emitted() shouldBe listOf(1, 1)
            clock.adjust(30.seconds)
            running.done shouldBe Exit.Done(listOf(1, 1, 1, 2))
            lines.map { it.level to it.at } shouldBe
                listOf(
                    LogLevel.Warn to Instant.EPOCH,
                    LogLevel.Warn to Instant.EPOCH.plusSeconds(30),
                )
            lines.map { it.origin }.distinct() shouldBe listOf("dev.surefold.stream.TimedKt")
        }

        test("stopping a run that waits on the clock ends it Done with what it had") {
            val clock = TestClock()
            val running = Stream.tick(1.seconds, "t").runCollect().start(TestStreams(clock))
            clock.adjust(2.seconds)

            running.stop()

            running.done shouldBe Exit.Done(listOf("t", "t"))
            clock.adjust(10.seconds)
            running.emitted() shouldBe listOf("t", "t")
        }

        test("a clock no run waits on moves as it always has") {
            val clock = TestClock()

            Stream.of(1).runCollect().run(TestStreams(clock))
            clock.adjust(250.milliseconds)

            clock.now() shouldBe Instant.EPOCH.plusMillis(250)
        }
    }
}
