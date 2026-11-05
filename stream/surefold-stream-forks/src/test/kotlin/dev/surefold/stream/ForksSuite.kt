package dev.surefold.stream

import dev.surefold.LogLevel
import dev.surefold.LogLine
import dev.surefold.Logger
import dev.surefold.fail.Either
import dev.surefold.fail.bind
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldBeLessThan
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withClue
import dev.surefold.logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private const val SLOW_MILLIS = 500L
private const val SEED = 156

// Forks holds nothing of a run's own, so one serves every test.
private val forks = Forks()

private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

private fun <E, A : Any> Stream<E, A>.collected(): Exit<E, List<A>> = runCollect().run(forks).settled()

private val naturals: Iterable<Int> = generateSequence(1) { it + 1 }.asIterable()

/** A run on surefold's own forks: one pull loop, on one virtual thread, and the same exits Pekko answers with. */
object ForksSuite : Suite() {
    private data class Odd(val value: Int)

    init {
        test(
            "on real time, a window closes with what it held while upstream is blocked, and a full group goes at once",
        ) {
            val given = LinkedBlockingQueue<Int>(listOf(1, 2))
            val done = -1
            val exit =
                Stream.blocking(
                        open = { given },
                        next = { queue -> queue.take().takeIf { it != done } },
                        wake = { queue -> queue.put(done) },
                        close = {},
                    )
                    .groupedWithin(3, 50.milliseconds)
                    // The first window can only close on time, with upstream blocked; what follows fills a group of
                    // three.
                    .map { group -> group.also { if (it == listOf(1, 2)) given.addAll(listOf(3, 4, 5, done)) } }
                    .runCollect()
                    .run(Forks())
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS)

            exit shouldBe Exit.Done(listOf(listOf(1, 2), listOf(3, 4, 5)))
        }

        test("on real time, groupedWithin hands on every element in order, a group at a time") {
            val exit =
                Stream.from(1..10_000)
                    .groupedWithin(100, 1.minutes)
                    .runCollect()
                    .run(Forks())
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS)

            exit.shouldBeInstanceOf<Exit.Done<List<List<Int>>>> { done ->
                val groups = done.value
                groups.flatten() shouldBe (1..10_000).toList()
                groups.all { it.size == 100 } shouldBe true
            }
        }

        test("a raise in an element body ends the run Failed with what it raised") {
            val exit = Stream.of(1, 2, 3).mapOrFail { if (it == 2) fail(Odd(it)) else it }.collected()

            exit shouldBe Exit.Failed(Odd(2))
        }

        test("a bind on a Left in an element body is the same failure") {
            val lookup: (Int) -> Either<Odd, Int> = { if (it % 2 == 0) Odd(it).left() else it.right() }

            val exit = Stream.of(1, 3, 4, 5).mapOrFail { lookup(it).bind() }.collected()

            exit shouldBe Exit.Failed(Odd(4))
        }

        test("take ends an infinite stream, and the source is pulled no further than it asked") {
            val pulled = AtomicInteger()

            val exit = Stream.from(naturals).map { pulled.incrementAndGet() }.take(3).collected()

            exit shouldBe Exit.Done(listOf(1, 2, 3))
            pulled.get() shouldBe 3
        }

        test("every stage runs on a virtual thread that is not the caller's") {
            val caller = Thread.currentThread()

            val exit = Stream.of(1).map { Thread.currentThread() }.collected()

            exit.shouldBeInstanceOf<Exit.Done<List<Thread>>> { done ->
                val ran = done.value.single()
                withClue("the pull loop runs on its own fork") { (ran === caller) shouldBe false }
                ran.isVirtual shouldBe true
            }
        }

        test("a defect ends the run Died, naming the operator, the element and the line that wrote it") {
            val lines = ConcurrentLinkedQueue<LogLine>()
            val exit =
                logger.locally(Logger { lines += it }) {
                    Stream.of(1, 2)
                        .map { n ->
                            check(n < 2) { "too big" }
                            n
                        }
                        .collected()
                }

            exit.shouldBeInstanceOf<Exit.Died> { died ->
                died.cause.shouldBeInstanceOf<IllegalStateException>()
                died.cause.suppressed.single().message shouldContain "map died on 2, built at ForksSuite.kt:"
            }
            lines.map { it.level to it.origin } shouldBe listOf(LogLevel.Error to Pulling::class.java.name)
        }

        test("in a fused run a defect names the step that threw, its element and its line") {
            val fused = Stream.of(1, 2, 3).map { it + 1 }

            val steps =
                listOf(
                    "map" to
                        fused.map { n ->
                            check(n < 3) { "too big" }
                            n
                        },
                    "filter" to
                        fused.filter { n ->
                            check(n < 3) { "too big" }
                            true
                        },
                    "filterNot" to
                        fused.filterNot { n ->
                            check(n < 3) { "too big" }
                            false
                        },
                    "mapOrFail" to
                        fused.mapOrFail { n ->
                            check(n < 3) { "too big" }
                            n
                        },
                )

            steps.forAll { (operator, stream) ->
                withClue(operator) {
                    stream.collected().shouldBeInstanceOf<Exit.Died> { died ->
                        died.cause.suppressed.single().message shouldContain
                            "$operator died on 3, built at ForksSuite.kt:"
                    }
                }
            }
        }

        test("in a fused run a raise ends the run Failed, and a filter keeps what it is asked to") {
            val kept =
                Stream.of(1, 2, 3, 4, 5, 6)
                    .map { it * 3 }
                    .filter { it % 2 == 0 }
                    .filterNot { it == 12 }
                    .map { it + 1 }
                    .collected()
            val raised =
                Stream.of(1, 2, 3)
                    .map { it * 2 }
                    .mapOrFail { if (it == 4) fail(Odd(it)) else it }
                    .map { it + 1 }
                    .collected()

            kept shouldBe Exit.Done(listOf(7, 19))
            raised shouldBe Exit.Failed(Odd(4))
        }

        test("another backend's own value is refused by name before anything runs") {
            val pulled = AtomicInteger()
            val elsewhere =
                Stream<Nothing, Int>(Node.Native(Any(), BackendKey("Elsewhere"), "Elsewhere.source", "Here.kt:1"))

            val exit = Stream.from(naturals).map { pulled.incrementAndGet() }.merge(elsewhere).collected()

            exit.shouldBeInstanceOf<Exit.Died> { died ->
                died.cause.message shouldContain "Elsewhere.source, built at Here.kt:1, holds a Elsewhere value"
            }
            pulled.get() shouldBe 0
        }

        test("stop ends a run that would not end, Done with what it had") {
            val running = Stream.from(naturals).runFold(0) { count, _ -> count + 1 }.start(forks)

            running.close()

            running.exit.settled().shouldBeInstanceOf<Exit.Done<Int>>()
        }

        test("the bounds answer as their names say") {
            Stream.of(1, 2, 3, 4, 5).drop(2).collected() shouldBe Exit.Done(listOf(3, 4, 5))
            Stream.of(1, 2, 3, 1).takeWhile { it < 3 }.collected() shouldBe Exit.Done(listOf(1, 2))
            Stream.of(1, 2, 3, 1).dropWhile { it < 3 }.collected() shouldBe Exit.Done(listOf(3, 1))
            Stream.of(1, 2, 3, 4).filterNot { it % 2 == 0 }.collected() shouldBe Exit.Done(listOf(1, 3))
            Stream.of(1, 2).drop(5).collected() shouldBe Exit.Done(emptyList())
        }

        test("grouping, scanning and state carry across elements") {
            Stream.of(1, 2, 3, 4, 5).grouped(2).collected() shouldBe
                Exit.Done(listOf(listOf(1, 2), listOf(3, 4), listOf(5)))
            Stream.of(1, 2, 3).scan(0) { total, n -> total + n }.collected() shouldBe Exit.Done(listOf(0, 1, 3, 6))
            Stream.of(1, 2, 3)
                .statefulMap({ 0 }, { seen, n -> (seen + 1) to "$n@$seen" }, { seen -> "end@$seen" })
                .collected() shouldBe Exit.Done(listOf("1@0", "2@1", "3@2", "end@3"))
            Stream.of(1, 2, 3).mapConcat { n -> List(n) { n } }.collected() shouldBe Exit.Done(listOf(1, 2, 2, 3, 3, 3))
        }

        test("the failure operators replace, recover and name as Pekko's do") {
            val failing = Stream.of(1, 2).mapOrFail { if (it == 2) fail(Odd(it)) else it }

            failing.either().collected() shouldBe Exit.Done(listOf(1.right(), Odd(2).left()))
            failing.catchAll { Stream.of(9) }.collected() shouldBe Exit.Done(listOf(1, 9))
            failing.mapError { it.value }.collected() shouldBe Exit.Failed(2)
            Stream.of(1.right(), Odd(3).left(), 5.right()).absolve().collected() shouldBe Exit.Failed(Odd(3))
            Stream.empty().orFailIfEmpty(Odd(0)).collected() shouldBe Exit.Failed(Odd(0))
        }

        test("streams join end to end, pairwise, and one inside another") {
            Stream.of(1, 2).concat(Stream.of(3)).collected() shouldBe Exit.Done(listOf(1, 2, 3))
            Stream.of(3).prepend(Stream.of(1, 2)).collected() shouldBe Exit.Done(listOf(1, 2, 3))
            Stream.of(1, 2, 3).zip(Stream.of("a", "b")).collected() shouldBe Exit.Done(listOf(1 to "a", 2 to "b"))
            Stream.of(1, 2, 3)
                .flatMapConcat { n -> Stream.from(List(n) { n }) }
                .collected()
                .shouldBeInstanceOf<Exit.Done<List<Int>>> { done ->
                    done.value shouldContainExactly listOf(1, 2, 2, 3, 3, 3)
                }
        }

        test("a stage's value is the one element, and null from it is the defect fromStage names") {
            val stage: CompletionStage<String> = java.util.concurrent.CompletableFuture.completedFuture("ready")

            @Suppress("UNCHECKED_CAST")
            val nulled = java.util.concurrent.CompletableFuture.completedFuture(null) as CompletionStage<String>

            Stream.fromStage(stage).collected() shouldBe Exit.Done(listOf("ready"))
            Stream.fromStage(nulled, ifNull = Odd(0)).collected() shouldBe Exit.Failed(Odd(0))
        }

        test(
            "mapPar answers in the order the elements came, with never more bodies at once than it was given",
        ) {
            val running = AtomicInteger()
            val most = AtomicInteger()

            val exit =
                Stream.from(1..40)
                    .mapPar(8) { n ->
                        most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                        Thread.sleep((41 - n).toLong() % 7)
                        running.decrementAndGet()
                        n * 10
                    }
                    .collected()

            exit shouldBe Exit.Done((1..40).map { it * 10 })
            withClue("at most eight at once, and more than one, or it was not parallel") {
                (most.get() in 2..8) shouldBe true
            }
        }

        test("a raise in one mapPar body ends the run Failed, and interrupts the bodies still running") {
            val interrupted = AtomicInteger()

            val exit =
                Stream.from(1..8)
                    .mapPar<Odd, Int, Int>(8) { n ->
                        if (n == 1) {
                            Thread.sleep(50)
                            fail(Odd(n))
                        }
                        try {
                            Thread.sleep(60_000)
                        } catch (stopped: InterruptedException) {
                            interrupted.incrementAndGet()
                            throw stopped
                        }
                        n
                    }
                    .collected()

            exit shouldBe Exit.Failed(Odd(1))
            withClue("the seven still sleeping were interrupted, and had stopped before the exit completed") {
                interrupted.get() shouldBe 7
            }
        }

        test("take after mapPar ends the run, and no body is left running behind it") {
            val running = AtomicInteger()

            val exit =
                Stream.from(naturals)
                    .mapPar(4) { n ->
                        running.incrementAndGet()
                        try {
                            if (n > 3) Thread.sleep(60_000)
                        } finally {
                            running.decrementAndGet()
                        }
                        n
                    }
                    .take(3)
                    .collected()

            // 1 and 3 share a key and keep their order; 2 has its own, and may pass either.
            exit.shouldBeInstanceOf<Exit.Done<List<Int>>> { done ->
                val taken = done.value
                taken.sorted() shouldBe listOf(1, 2, 3)
                taken.filter { it % 2 == 1 } shouldBe listOf(1, 3)
            }
            running.get() shouldBe 0
        }

        test("a mapPar body that throws is the defect the run dies with, naming mapPar") {
            val exit =
                Stream.of(1, 2)
                    .mapPar(2) { n ->
                        check(n != 2) { "two" }
                        n
                    }
                    .collected()

            exit.shouldBeInstanceOf<Exit.Died> { died ->
                val messages = (listOf(died.cause) + died.cause.suppressed).joinToString { it.message.orEmpty() }
                messages shouldContain "mapPar"
            }
        }

        test("mapParKeyed refuses a parallelism with no room for a body, rather than waiting for one") {
            shouldThrow<IllegalArgumentException> { Stream.of(1).mapParKeyed(0, key = { it }) { n -> n } }
        }

        test("under mapParKeyed a slow element on one key does not hold back another key's results") {
            val arrived = ConcurrentLinkedQueue<Int>()

            val exit =
                Stream.from(0..9)
                    .mapParKeyed(8, key = { it % 2 }) { n ->
                        if (n == 0) Thread.sleep(SLOW_MILLIS)
                        n
                    }
                    .map { n -> n.also(arrived::add) }
                    .collected()

            exit.shouldBeInstanceOf<Exit.Done<List<Int>>> { done -> done.value.sorted() shouldBe (0..9).toList() }
            withClue("every odd result arrived ahead of the even one that slept, in order: $arrived") {
                arrived.filter { it % 2 == 1 } shouldContainExactly listOf(1, 3, 5, 7, 9)
                arrived.indexOf(9) shouldBeLessThan arrived.indexOf(0)
            }
        }

        test("under mapParKeyed every key's results keep their order") {
            val random = Random(SEED)
            val delays = List(400) { random.nextLong(3) }

            val exit =
                Stream.from(0 until 400)
                    .mapParKeyed(16, key = { it % 7 }) { n ->
                        Thread.sleep(delays[n])
                        n
                    }
                    .collected()

            exit.shouldBeInstanceOf<Exit.Done<List<Int>>> { done ->
                val results = done.value
                results.sorted() shouldBe (0 until 400).toList()
                results.groupBy { it % 7 } shouldBe (0 until 400).groupBy { it % 7 }
            }
        }

        test(
            "a raise in one mapParKeyed body ends the run Failed, and interrupts the bodies still running",
        ) {
            val interrupted = AtomicInteger()

            val exit =
                Stream.from(1..8)
                    .mapParKeyed<Odd, Int, Int>(8, key = { it }) { n ->
                        if (n == 1) {
                            Thread.sleep(50)
                            fail(Odd(n))
                        }
                        try {
                            Thread.sleep(60_000)
                        } catch (stopped: InterruptedException) {
                            interrupted.incrementAndGet()
                            throw stopped
                        }
                        n
                    }
                    .collected()

            exit shouldBe Exit.Failed(Odd(1))
            interrupted.get() shouldBe 7
        }

        test("a mapParKeyed body that throws is the defect the run dies with, naming mapPar") {
            val exit =
                Stream.of(1, 2)
                    .mapParKeyed(2, key = { it }) { n ->
                        check(n != 2) { "two" }
                        n
                    }
                    .collected()

            exit.shouldBeInstanceOf<Exit.Died> { died ->
                val messages = (listOf(died.cause) + died.cause.suppressed).joinToString { it.message.orEmpty() }
                messages shouldContain "mapPar"
            }
        }

        test(
            "under mapParKeyed a key that throws is the defect the run dies with, after what came before it",
        ) {
            val exit =
                Stream.of(1, 2, 3)
                    .mapParKeyed(
                        2,
                        key = { n ->
                            check(n != 3) { "no key" }
                            n
                        },
                    ) {
                        it
                    }
                    .collected()

            exit.shouldBeInstanceOf<Exit.Died> { died ->
                val messages = (listOf(died.cause) + died.cause.suppressed).joinToString { it.message.orEmpty() }
                messages shouldContain "no key"
            }
        }

        test("take after mapParKeyed ends the run, and no body is left running behind it") {
            val running = AtomicInteger()

            val exit =
                Stream.from(naturals)
                    .mapParKeyed(4, key = { it % 2 }) { n ->
                        running.incrementAndGet()
                        try {
                            if (n > 3) Thread.sleep(60_000)
                        } finally {
                            running.decrementAndGet()
                        }
                        n
                    }
                    .take(3)
                    .collected()

            // 1 and 3 share a key and keep their order; 2 has its own, and may pass either.
            exit.shouldBeInstanceOf<Exit.Done<List<Int>>> { done ->
                val taken = done.value
                taken.sorted() shouldBe listOf(1, 2, 3)
                taken.filter { it % 2 == 1 } shouldBe listOf(1, 3)
            }
            running.get() shouldBe 0
        }

        test("mapAsync keeps no more of its stages in flight than it was given, and answers in order") {
            val inFlight = AtomicInteger()
            val most = AtomicInteger()
            val delayed = CompletableFuture.delayedExecutor(2, TimeUnit.MILLISECONDS)

            val exit =
                Stream.from(1..30)
                    .mapAsync(3) { n ->
                        most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                        CompletableFuture.supplyAsync(
                            {
                                inFlight.decrementAndGet()
                                n
                            },
                            delayed,
                        )
                    }
                    .collected()

            exit shouldBe Exit.Done((1..30).toList())
            withClue("at most three stages at once, and more than one") { (most.get() in 2..3) shouldBe true }
        }

        test("tick on Forks waits on real time, one element per interval") {
            val started = System.nanoTime()

            val exit = Stream.tick(20.milliseconds, "t").take(3).collected()

            exit shouldBe Exit.Done(listOf("t", "t", "t"))
            withClue("three ticks at 20 ms take at least 60 ms") {
                ((System.nanoTime() - started) >= 60_000_000L) shouldBe true
            }
        }

        test("groupedWithin on Forks closes a window on real time while upstream is slow") {
            val exit =
                Stream.from(1..6)
                    .map { n ->
                        Thread.sleep(30)
                        n
                    }
                    .groupedWithin(100, 70.milliseconds)
                    .collected()

            exit.shouldBeInstanceOf<Exit.Done<List<List<Int>>>> { done ->
                val groups = done.value
                withClue("every element in order, in windows the clock closed rather than one full group: $groups") {
                    groups.flatten() shouldBe (1..6).toList()
                    (groups.size > 1) shouldBe true
                }
            }
        }

        test("a restart on Forks waits its delay on real time, and runs the description again") {
            val attempts = AtomicInteger()
            val started = System.nanoTime()

            val exit =
                Stream.of(1, 2)
                    .map { n ->
                        check(!(n == 2 && attempts.incrementAndGet() == 1)) { "first time" }
                        n
                    }
                    .restartOnDefect(dev.surefold.Schedule.spaced(50.milliseconds))
                    .collected()

            val waited = System.nanoTime() - started
            exit shouldBe Exit.Done(listOf(1, 1, 2))
            (waited >= 50_000_000L) shouldBe true
        }
    }
}
