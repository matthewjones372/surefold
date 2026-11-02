package io.github.matthewjones372.surefold.stream

import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.withClue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * Every parity case on Forks and on Actors, with every thread it starts counted: run to the end, stopped as soon as it
 * starts, and stopped part way through. However a run ends, nothing it started is still running once its
 * exit has completed. Then the whole suite again, many times, to shake out what only shows up sometimes.
 */
object HardenedSuite : Suite() {
    private const val SETTLE_SECONDS = 10L
    private const val DEFAULT_SOAK = 200

    private val parity = ParitySuite

    private val soak: Int = System.getProperty("surefold.stream.soak")?.toIntOrNull() ?: DEFAULT_SOAK

    private val naturals: Iterable<Int> = generateSequence(1) { it + 1 }.asIterable()

    /** Every backend a run can leak from, each made with the executor that counts what it starts. */
    private val backends: List<Pair<String, (Counted) -> StreamBackend>> = listOf(
        "Forks" to { counted -> Forks(counted) },
        "Actors" to { counted -> Actors(heldScope, on = counted) },
    )

    private fun Counted.leavesNothing(case: ParitySuite.Case, how: String) =
        withClue("${case.name}, $how: a thread it started is still running") { leftRunning() shouldBe 0 }

    /**
     * [body] on every backend, with a count of its own; a backend on actors must also have no run left once [body]
     * has seen every exit.
     */
    private fun every(body: (Counted, (Counted) -> StreamBackend) -> Unit) =
        backends.forAll { (name, make) ->
            val made = mutableListOf<StreamBackend>()
            withClue(name) {
                body(Counted()) { counted -> make(counted).also(made::add) }
                made.filterIsInstance<Actors>().forAll { it.running shouldBe 0 }
            }
        }

    /** Registers [name] for every parity case on every backend, each with a count and a backend of its own. */
    private fun onEveryBackend(name: String, body: (ParitySuite.Case, Counted, StreamBackend) -> Unit) =
        backends.forEach { (backendName, make) ->
            parity.cases().forEach { case ->
                test("$name [$backendName] [${case.name}]") {
                    val counted = Counted()
                    val backend = make(counted)
                    body(case, counted, backend)
                    listOfNotNull(backend as? Actors).forAll {
                        withClue("${case.name}: a run is left") { it.running shouldBe 0 }
                    }
                }
            }
        }

    init {
        onEveryBackend("every case run to the end leaves nothing running") { case, counted, backend ->
            parity.check(case, backend)
            counted.leavesNothing(case, "run to the end")
        }

        onEveryBackend(
            "every case stopped as soon as it starts ends, and leaves nothing running",
        ) { case, counted, backend ->
            val running = case.run().start(backend)
            running.stop()
            running.exit.toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)
            counted.leavesNothing(case, "stopped at once")
        }

        onEveryBackend(
            "every case stopped part way through ends, and leaves nothing running",
        ) { case, counted, backend ->
            val running = case.run().start(backend)
            Thread.sleep(Random.nextLong(0, 3))
            running.stop()
            running.exit.toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)
            counted.leavesNothing(case, "stopped part way")
        }

        test("the whole suite, many times over, answers the same and leaves nothing running") {
            every { counted, backend ->
                (1..soak).forAll {
                    parity.cases().forAll { case -> parity.check(case, backend(counted)) }
                }
                withClue("after $soak runs of every case") { counted.leftRunning() shouldBe 0 }
            }
        }

        test("a run stopped while a slow body is in flight ends at once, Done with what it had") {
            every { counted, backend ->
                val bodies = AtomicInteger()
                val running = Stream.from(1..100)
                    .mapPar(4) { n ->
                        bodies.incrementAndGet()
                        if (n > 2) Thread.sleep(60_000)
                        n
                    }
                    .runCollect()
                    .start(backend(counted))
                // The window refills before it waits, so a sixth body started means 1 and 2 have been taken.
                while (bodies.get() < 6) Thread.sleep(1)

                running.stop()

                running.exit.toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)
                    .shouldBeInstanceOf<Exit.Done<List<Int>>> { it.value shouldBe listOf(1, 2) }
                counted.leftRunning() shouldBe 0
            }
        }

        test("a run stopped while it waits on an empty buffer ends at once") {
            every { counted, backend ->
                val running = Stream.from(generateSequence(1) { it + 1 }.asIterable())
                    .map { n ->
                        if (n > 1) Thread.sleep(60_000)
                        n
                    }
                    .buffer(2)
                    .runCollect()
                    .start(backend(counted))
                Thread.sleep(50)

                running.stop()

                val exit = running.exit.toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)
                exit shouldBe Exit.Done(listOf(1))
                counted.leftRunning() shouldBe 0
            }
        }

        test("two endless streams merged and cut short by take end, and leave nothing running") {
            every { counted, backend ->
                val exit = Stream.from(naturals).merge(Stream.from(naturals).map { -it }).take(100).runCollect()
                    .run(backend(counted)).toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)

                exit.shouldBeInstanceOf<Exit.Done<List<Int>>> { it.value.size shouldBe 100 }
                counted.leftRunning() shouldBe 0
            }
        }

        test("a merge ends once both inputs have, with each input's elements in its own order") {
            every { counted, backend ->
                val exit = Stream.from(1..5_000).merge(Stream.from(1..3_000).map { -it }).runCollect()
                    .run(backend(counted)).toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)

                exit.shouldBeInstanceOf<Exit.Done<List<Int>>> { done ->
                    done.value.filter { it > 0 } shouldBe (1..5_000).toList()
                    done.value.filter { it < 0 } shouldBe (1..3_000).map { -it }
                }
                counted.leftRunning() shouldBe 0
            }
        }

        test("a failing input ends a merge Failed, and lets go of the other") {
            every { counted, backend ->
                val failing: Stream<ParitySuite.Odd, Int> = Stream.from(1..10).mapOrFail {
                    if (it ==
                        10
                    ) fail(ParitySuite.Odd(it)) else it
                }

                val exit = failing.merge(Stream.from(naturals).map { -it }).runCollect()
                    .run(backend(counted)).toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)

                exit shouldBe Exit.Failed(ParitySuite.Odd(10))
                counted.leftRunning() shouldBe 0
            }
        }

        test("flatMapMerge never runs more inner streams at once than its breadth") {
            every { counted, backend ->
                val running = AtomicInteger()
                val most = AtomicInteger()

                val exit = Stream.from(1..20)
                    .flatMapMerge(3) { n ->
                        Stream.of(n).map { m ->
                            most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                            Thread.sleep(2)
                            running.decrementAndGet()
                            m
                        }
                    }
                    .runCollect()
                    .run(backend(counted)).toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)

                exit.shouldBeInstanceOf<Exit.Done<List<Int>>> { it.value.sorted() shouldBe (1..20).toList() }
                withClue("at most three inner streams at once, and more than one") {
                    (most.get() in 2..3) shouldBe true
                }
                counted.leftRunning() shouldBe 0
            }
        }

        test("a failing inner stream ends a flatMapMerge Failed, and lets go of the others") {
            every { counted, backend ->
                val outer: Stream<ParitySuite.Odd, Int> = Stream.from(1..4)

                val exit = outer
                    .flatMapMerge(4) { n ->
                        if (n == 2) Stream.fail(ParitySuite.Odd(n)) else Stream.from(naturals).map { it * n }
                    }
                    .runCollect()
                    .run(backend(counted)).toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)

                exit shouldBe Exit.Failed(ParitySuite.Odd(2))
                counted.leftRunning() shouldBe 0
            }
        }

        test("conflate gives a slow reader what piled up, and loses nothing") {
            every { counted, backend ->
                val exit = Stream.from(1..200)
                    .conflateWithSeed({ listOf(it) }, { batch, n -> batch + n })
                    .map { batch ->
                        Thread.sleep(1)
                        batch
                    }
                    .runCollect()
                    .run(backend(counted)).toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)

                exit.shouldBeInstanceOf<Exit.Done<List<List<Int>>>> { done ->
                    val batches = done.value
                    withClue("every element, in order, and fewer batches than elements") {
                        batches.flatten() shouldBe (1..200).toList()
                        (batches.size < 200) shouldBe true
                    }
                }
                counted.leftRunning() shouldBe 0
            }
        }

        test("an endless source that never blocks, conflated and cut short, leaves nothing running") {
            every { counted, backend ->
                val exit = Stream.from(naturals).conflateWithSeed({ 1 }, { count, _ -> count + 1 }).take(3).runCollect()
                    .run(backend(counted)).toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)

                exit.shouldBeInstanceOf<Exit.Done<List<Int>>> { it.value.size shouldBe 3 }
                counted.leftRunning() shouldBe 0
            }
        }

        test("a run stopped while a restartOnDefect waits on its next tick ends, and does not restart") {
            every { counted, backend ->
                // Like an outbox relay with nothing to send: ticks that never become an element, so the loop is
                // never back between elements to see the stop itself.
                val running = Stream.tick(20.milliseconds, 1)
                    .mapPar(2) { it }
                    .mapConcat { emptyList<Int>() }
                    .restartOnDefect(dev.surefold.Schedule.spaced(10.milliseconds))
                    .runCollect()
                    .start(backend(counted))
                Thread.sleep(70)

                running.stop()

                running.exit.toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)
                    .shouldBeInstanceOf<Exit.Done<List<Int>>>()
                counted.leftRunning() shouldBe 0
            }
        }

        test("the count sees a task left running, so a leak would turn these red") {
            val counted = Counted()
            counted.execute { Thread.sleep(5_000) }

            counted.leftRunning(within = kotlin.time.Duration.parse("100ms")) shouldBe 1
        }
    }
}
