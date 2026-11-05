package dev.surefold.stream

import dev.surefold.Schedule
import dev.surefold.TestClock
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.withClue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

private const val GENEROUS_SECONDS = 30L

/** A resource whose reads block until there is something to read, or it is woken. */
private class Queue(vararg items: Int) {
    val items = ArrayDeque(items.toList())
    val waiting = CountDownLatch(1)
    val woken = CountDownLatch(1)
    val closed = AtomicInteger()

    fun next(): Int? {
        items.removeFirstOrNull()?.let {
            return it
        }
        waiting.countDown()
        woken.await()
        return null
    }
}

private fun blocking(queue: Queue): Stream<Nothing, Int> =
    Stream.blocking(
        open = { queue },
        next = { it.next() },
        wake = { it.woken.countDown() },
        close = { it.closed.incrementAndGet() },
    )

private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
    toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS)

/** A read that blocks until it is woken, and only then: an interrupt does not end it, as with a Kafka poll. */
private class Deaf(private val first: Int) {
    private var given = false
    val waiting = CountDownLatch(1)
    val woken = CountDownLatch(1)
    val closed = AtomicInteger()

    fun next(): Int? {
        if (!given) return first.also { given = true }
        waiting.countDown()
        while (woken.count > 0) {
            try {
                woken.await()
            } catch (_: InterruptedException) {
                // Not a wake: only the source's own wake ends this read.
            }
        }
        return null
    }
}

private fun deaf(source: Deaf): Stream<Nothing, Int> =
    Stream.blocking(
        open = { source },
        next = { it.next() },
        wake = { it.woken.countDown() },
        close = { it.closed.incrementAndGet() },
    )

/**
 * Runs [stream] on Forks, stops it once [source]'s read blocks, checks the read was reached, and answers the run's
 * exit.
 */
private fun stoppedWhileBlocked(stream: Stream<Nothing, Int>, source: Deaf): Exit<*, *> {
    val running = stream.runCollect().start(Forks())
    withClue("the source's read was reached") {
        source.waiting.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true
    }
    running.stop()
    return running.exit.settled()
}

/** A resource opened per run, read by blocking on the pulling thread, and closed once however the run ended. */
object BlockingSuite : Suite() {
    init {
        test("a resource that runs out ends the stream, and is closed once") {
            val queue = Queue(1, 2, 3).also { it.woken.countDown() }

            val exit = blocking(queue).runCollect().run(Forks()).settled()

            exit shouldBe Exit.Done(listOf(1, 2, 3))
            queue.closed.get() shouldBe 1
        }

        test("a stop while next is blocked wakes it, and the run ends Done with what arrived") {
            val queue = Queue(1, 2)
            val running = blocking(queue).runCollect().start(Forks())
            queue.waiting.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true

            running.stop()

            withClue("without wake the loop waits for a next that never returns") {
                running.exit.settled() shouldBe Exit.Done(listOf(1, 2))
            }
            queue.closed.get() shouldBe 1
        }

        test("a take that is satisfied closes the resource it stopped reading") {
            val queue = Queue(1, 2, 3)

            val exit = blocking(queue).take(2).runCollect().run(Forks()).settled()

            exit shouldBe Exit.Done(listOf(1, 2))
            queue.closed.get() shouldBe 1
        }

        test("a next that throws without a wake is a defect, and the resource is still closed") {
            val closed = AtomicInteger()
            val exit =
                Stream.blocking(
                        open = { Unit },
                        next = { _ -> error("the cursor broke") },
                        wake = {},
                        close = { closed.incrementAndGet() },
                    )
                    .runCollect()
                    .run(Forks())
                    .settled()

            (exit as Exit.Died).cause.message shouldBe "the cursor broke"
            closed.get() shouldBe 1
        }

        test("a close that throws ends a run that was otherwise Done as Died") {
            val exit =
                Stream.blocking(
                        open = { Unit },
                        next = { _ -> null as Int? },
                        wake = {},
                        close = { _ -> error("the connection would not close") },
                    )
                    .runCollect()
                    .run(Forks())
                    .settled()

            (exit as Exit.Died).cause.message shouldBe "the connection would not close"
        }

        test("a read that fails closes its resource before restartOnDefect opens another") {
            val opened = AtomicInteger()
            val closed = AtomicInteger()
            val exit =
                Stream.blocking(
                        open = { opened.incrementAndGet() },
                        next = { n ->
                            if (n == 1) error("the first connection dropped") else n.takeIf { closed.get() == 1 }
                        },
                        wake = {},
                        close = { closed.incrementAndGet() },
                    )
                    .restartOnDefect(Schedule.recurs<Throwable>(1))
                    .take(1)
                    .runCollect()
                    .run(Forks())
                    .settled()

            exit shouldBe Exit.Done(listOf(2))
            withClue("the failed resource was closed before the second read, and the second when the run ended") {
                closed.get() shouldBe 2
            }
        }

        test(
            "a restart wakes a source still reading on another fork, and closes it once the read has returned",
        ) {
            class Attempt(val number: Int) {
                val reads = AtomicInteger()
                val reading = AtomicBoolean(false)
                val woken = CountDownLatch(1)
            }
            val attempts = AtomicInteger()
            val events = CopyOnWriteArrayList<String>()
            val overlapped = CopyOnWriteArrayList<Int>()

            val exit =
                Stream.blocking(
                        open = { Attempt(attempts.incrementAndGet()).also { events += "open ${it.number}" } },
                        next = { attempt ->
                            attempt.reading.set(true)
                            try {
                                if (attempt.reads.getAndIncrement() == 0) {
                                    attempt.number
                                } else {
                                    attempt.woken.await(GENEROUS_SECONDS, TimeUnit.SECONDS)
                                    null
                                }
                            } finally {
                                attempt.reading.set(false)
                            }
                        },
                        wake = { it.woken.countDown() },
                        close = { attempt ->
                            if (attempt.reading.get()) overlapped += attempt.number
                            events += "close ${attempt.number}"
                        },
                    )
                    // The window reads its source on a fork of its own, as a Kafka consumer's transactions do.
                    .groupedWithin(1, GENEROUS_SECONDS.seconds)
                    .map { batch -> batch.also { check(it != listOf(1)) { "the first attempt's batch fails" } } }
                    .restartOnDefect(Schedule.recurs<Throwable>(1))
                    .take(1)
                    .runCollect()
                    .run(Forks())
                    .settled()

            exit shouldBe Exit.Done(listOf(listOf(2)))
            withClue("no resource was closed while a read of it was still running") {
                overlapped.shouldBeEmpty()
            }
            events shouldBe listOf("open 1", "close 1", "open 2", "close 2")
        }

        test("a stage that is done is passed on while the source blocks for the next element") {
            val queue = Queue(1)

            val exit =
                blocking(queue)
                    .mapAsync(4) { n -> CompletableFuture.completedFuture(n * 10) }
                    .take(1)
                    .runCollect()
                    .run(Forks())
                    .settled()

            exit shouldBe Exit.Done(listOf(10))
            withClue("the source was let go of while it blocked, and closed once") {
                queue.closed.get() shouldBe 1
            }
        }

        test("a mapPar body that is done is passed on while the source blocks for the next element") {
            val queue = Queue(1)

            val exit = blocking(queue).mapPar(4) { n -> n * 10 }.take(1).runCollect().run(Forks()).settled()

            exit shouldBe Exit.Done(listOf(10))
            withClue("the source was let go of while it blocked, and closed once") {
                queue.closed.get() shouldBe 1
            }
        }

        test("a stop wakes a source blocked inside flatMapMerge, and closes it once") {
            val source = Deaf(1)

            val exit = stoppedWhileBlocked(Stream.of(0).flatMapMerge(2) { deaf(source) }, source)

            withClue("an inner stream is pulled on a fork, which must still find the run's resources to be woken") {
                (exit is Exit.Done) shouldBe true
            }
            source.closed.get() shouldBe 1
        }

        test("a stop wakes a source behind a concat upstream of a buffer, and closes it once") {
            val source = Deaf(1)

            val exit = stoppedWhileBlocked(Stream.of(0).concat(deaf(source)).buffer(4), source)

            withClue("concat builds its next stream on the buffer's fork") {
                (exit is Exit.Done) shouldBe true
            }
            source.closed.get() shouldBe 1
        }

        test("on a test's clock, a source inside flatMapMerge that a take stops reading is closed once") {
            // A source that never runs out and never blocks: a stage that blocked would keep its turn on the clock.
            val read = AtomicInteger()
            val closed = AtomicInteger()
            val endless =
                Stream.blocking(
                    open = { Unit },
                    next = { _ -> read.incrementAndGet() },
                    wake = {},
                    close = { closed.incrementAndGet() },
                )

            val exit =
                Stream.of(0)
                    .flatMapMerge(2) { endless }
                    .take(1)
                    .runCollect()
                    .run(ForksOnClock(TestClock(), name = "OnClock"))
                    .settled()

            exit shouldBe Exit.Done(listOf(1))
            withClue("only the run can close a source it stopped reading, so a worker on the clock must find it") {
                closed.get() shouldBe 1
            }
        }
    }
}
