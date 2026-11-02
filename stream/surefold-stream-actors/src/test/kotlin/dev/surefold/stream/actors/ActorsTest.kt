package dev.surefold.stream.actors

import dev.surefold.Schedule
import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeInstanceOf
import dev.surefold.assert.shouldThrow
import dev.surefold.scope
import dev.surefold.stream.Actors
import dev.surefold.stream.Exit
import dev.surefold.stream.Stream
import dev.surefold.stream.StreamSpi
import dev.surefold.stream.blocking
import dev.surefold.stream.buffer
import dev.surefold.stream.conflateWithSeed
import dev.surefold.stream.filter
import dev.surefold.stream.flatMapMerge
import dev.surefold.stream.from
import dev.surefold.stream.groupedWithin
import dev.surefold.stream.map
import dev.surefold.stream.mapOrFail
import dev.surefold.stream.mapPar
import dev.surefold.stream.merge
import dev.surefold.stream.of
import dev.surefold.stream.restartOnDefect
import dev.surefold.stream.run
import dev.surefold.stream.runCollect
import dev.surefold.stream.runFold
import dev.surefold.stream.start
import dev.surefold.stream.take
import dev.surefold.stream.tick
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/** A stream with no end: a run of it ends only when it is stopped. */
private fun endless() = Stream.from(Iterable { generateSequence(1) { it + 1 }.iterator() })

/**
 * Runs on Actors, on Jupiter rather than surefold-junit (spec 0197). "runs without end share the runners" keeps every
 * virtual-thread carrier busy on purpose, and surefold-junit runs a test's body on a virtual thread, so the body could
 * not get back to close the runs, and their lists filled the heap.
 */
@OptIn(StreamSpi::class)
class ActorsTest {

    @Test
    fun `a run pulls its whole chain on an actor, in batches, and ends with the answer`() {
        val exit = scope {
            Stream.from(1..1_000).map { it * 2 }.filter { it % 3 == 0 }.runFold(0) { sum, n -> sum + n }
                .run(Actors(this, batch = 7)).toCompletableFuture().join()
        }

        exit shouldBe Exit.Done((1..1_000).map { it * 2 }.filter { it % 3 == 0 }.sum())
    }

    @Test
    fun `a declared failure ends the run Failed, and a throw ends it Died`() {
        val exits = scope {
            val actors = Actors(this)
            val failed = Stream.from(1..10).mapOrFail { if (it == 5) fail("five") else it }.runCollect()
                .run(actors).toCompletableFuture().join()
            val died = Stream.from(1..10).map { check(it < 5) { "too big" } }.runCollect()
                .run(actors).toCompletableFuture().join()
            failed to died
        }

        exits.first shouldBe Exit.Failed("five")
        exits.second.shouldBeInstanceOf<Exit.Died>()
    }

    @Test
    fun `runs without end share the runners, so a short run started after them still ends`() {
        val answer = scope {
            val actors = Actors(this)
            val endless = (1..Runtime.getRuntime().availableProcessors() * 4).map {
                endless().runCollect().start(actors)
            }
            val short = Stream.from(1..3).runCollect().run(actors).toCompletableFuture().join()
            endless.forEach { it.close() }
            short to actors.running
        }

        answer shouldBe (Exit.Done(listOf(1, 2, 3)) to 0)
    }

    @Test
    fun `a stop wakes a source it is blocked in, and the run is Done with what it had`() {
        val answer = scope {
            val actors = Actors(this)
            val given = LinkedBlockingQueue<Int>()
            val taken = CountDownLatch(2)
            val running = Stream.blocking(
                open = { given },
                next = { queue -> queue.take().also { taken.countDown() }.takeIf { it > 0 } },
                wake = { queue -> queue.put(0) },
                close = { },
            ).runCollect().start(actors)
            given.put(1)
            given.put(2)
            taken.await()

            running.close()
            running.exit.toCompletableFuture().join() to actors.running
        }

        answer shouldBe (Exit.Done(listOf(1, 2)) to 0)
    }

    @Test
    fun `mapPar runs its bodies on its workers, never more at once than it allows, and answers in order`() {
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        // Three bodies meet at the barrier each time, so a mapPar(3) that ran them one at a time would never pass it.
        val together = CyclicBarrier(3)
        val exit = scope {
            Stream.from(1..9).mapPar(3) { n ->
                most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                together.await(1, TimeUnit.MINUTES)
                inFlight.decrementAndGet()
                n * 10
            }.runCollect().run(Actors(this)).toCompletableFuture().join()
        }

        exit shouldBe Exit.Done((1..9).map { it * 10 })
        most.get() shouldBe 3
    }

    @Test
    fun `a mapPar body that raises ends the run Failed, and one that throws ends it Died`() {
        val exits = scope {
            val actors = Actors(this)
            val numbers: Stream<String, Int> = Stream.from(1..10)
            val failed = numbers.mapPar(2) { if (it == 4) fail("four") else it }.runCollect()
                .run(actors).toCompletableFuture().join()
            val died = Stream.from(1..10).mapPar(2) { check(it < 4) { "too big" } }.runCollect()
                .run(actors).toCompletableFuture().join()
            failed to died
        }

        exits.first shouldBe Exit.Failed("four")
        exits.second.shouldBeInstanceOf<Exit.Died>()
    }

    @Test
    fun `a buffer hands on every element in order, and what upstream threw after what came before it`() {
        val exits = scope {
            val actors = Actors(this, batch = 2)
            val all = Stream.from(1..100).buffer(3).runCollect().run(actors).toCompletableFuture().join()
            val threw = Stream.from(1..10).map { check(it < 6) { "six" } }.buffer(3).runCollect()
                .run(actors).toCompletableFuture().join()
            all to threw
        }

        exits.first shouldBe Exit.Done((1..100).toList())
        exits.second.shouldBeInstanceOf<Exit.Died>().cause.message shouldBe "six"
    }

    @Test
    fun `the operators beside the loop run on the run's child actors, and start nothing on its executor`() {
        val started = AtomicInteger()
        val counting = Executor { task ->
            started.incrementAndGet()
            Thread.ofVirtual().start(task)
        }
        val exit = scope {
            Stream.from(1..50).mapPar(4) { it + 1 }.buffer(8)
                .merge(Stream.from(1..3).flatMapMerge(2) { Stream.of(it * 1000) })
                .conflateWithSeed({ listOf(it) }, { batch, n -> batch + n })
                .runCollect().run(Actors(this, on = counting)).toCompletableFuture().join()
        }

        exit.shouldBeInstanceOf<Exit.Done<List<List<Int>>>>().value.flatten().sorted() shouldBe
            ((2..51) + listOf(1000, 2000, 3000)).toList()
        started.get() shouldBe 0
    }

    @Test
    fun `merge keeps each stream's order, and flatMapMerge overlaps as many inner streams as its breadth`() {
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        val together = CyclicBarrier(3)
        val exits = scope {
            val actors = Actors(this, batch = 3)
            val merged = Stream.from(1..50).merge(Stream.from(101..150)).runCollect()
                .run(actors).toCompletableFuture().join()
            val flat = Stream.from(1..9).flatMapMerge<Nothing, Nothing, Int, Int>(3) { n ->
                Stream.of(n).map {
                    most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                    together.await(1, TimeUnit.MINUTES)
                    inFlight.decrementAndGet()
                    it
                }
            }.runCollect().run(actors).toCompletableFuture().join()
            merged to flat
        }

        val merged = exits.first.shouldBeInstanceOf<Exit.Done<List<Int>>>().value
        merged.filter { it <= 50 } shouldBe (1..50).toList()
        merged.filter { it > 100 } shouldBe (101..150).toList()
        exits.second.shouldBeInstanceOf<Exit.Done<List<Int>>>().value.sorted() shouldBe (1..9).toList()
        most.get() shouldBe 3
    }

    @Test
    fun `conflate folds what upstream sent while the reader was busy, and loses nothing`() {
        val sent = CountDownLatch(200)
        val exit = scope {
            Stream.from(1..200).map { it.also { sent.countDown() } }
                .conflateWithSeed({ listOf(it) }, { batch, n -> batch + n })
                .map { batch -> batch.also { sent.await() } }
                .runCollect().run(Actors(this)).toCompletableFuture().join()
        }

        val batches = exit.shouldBeInstanceOf<Exit.Done<List<List<Int>>>>().value
        batches.flatten() shouldBe (1..200).toList()
        (batches.size < 200) shouldBe true
    }

    @Test
    fun `tick, groupedWithin and restartOnDefect run on the run's clock, as on Forks`() {
        val tries = AtomicInteger()
        val exits = scope {
            val actors = Actors(this)
            val ticks = Stream.tick(1.milliseconds, "tick").take(3).runCollect()
                .run(actors).toCompletableFuture().join()
            val groups = Stream.from(1..5).groupedWithin(2, 1.hours).runCollect()
                .run(actors).toCompletableFuture().join()
            val restarted = Stream.of(1).map { if (tries.incrementAndGet() == 1) error("first try") else it }
                .restartOnDefect(Schedule.recurs(1)).runCollect()
                .run(actors).toCompletableFuture().join()
            Triple(ticks, groups, restarted)
        }

        exits shouldBe Triple(
            Exit.Done(listOf("tick", "tick", "tick")),
            Exit.Done(listOf(listOf(1, 2), listOf(3, 4), listOf(5))),
            Exit.Done(listOf(1)),
        )
    }

    @Test
    fun `a batch of no elements is refused`() {
        scope {
            shouldThrow<IllegalArgumentException> { Actors(this, batch = 0) }
        }
    }
}
