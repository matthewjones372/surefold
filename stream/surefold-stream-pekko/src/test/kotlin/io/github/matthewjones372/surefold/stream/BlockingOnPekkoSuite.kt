package io.github.matthewjones372.surefold.stream

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import dev.surefold.junit.withClue
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A blocking source on Pekko: read on the blocking-IO dispatcher, woken by stop(), closed once. */
object BlockingOnPekkoSuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-blocking-on-pekko-test")

    private const val GENEROUS_SECONDS = 30L

    // Long enough for one element to cross to the stream many times over.
    private const val NOT_SEEN_MILLIS = 200L

    private class Queue(vararg items: Int) {
        val items = ArrayDeque(items.toList())
        val waiting = CountDownLatch(1)
        val woken = CountDownLatch(1)
        val closed = AtomicInteger()

        @Volatile
        var readOn: String = ""

        fun next(): Int? {
            readOn = Thread.currentThread().name
            items.removeFirstOrNull()?.let { return it }
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

    init {
        test(
            "a resource that runs out ends the stream, read on the blocking-IO dispatcher, and closed once",
        ) { pekko ->
            val queue = Queue(1, 2, 3).also { it.woken.countDown() }

            blocking(queue).runCollect().run(pekko.system).settled() shouldBe Exit.Done(listOf(1, 2, 3))
            withClue("a blocked read must never hold one of the stream's own threads") {
                queue.readOn shouldContain "blocking-io"
            }
            queue.closed.get() shouldBe 1
        }

        test("a stop while next is blocked wakes it, and what was read still reaches the sink") { pekko ->
            val queue = Queue(1, 2)
            val running = blocking(queue).runCollect().start(pekko.system)
            queue.waiting.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true

            running.stop()

            running.exit.settled() shouldBe Exit.Done(listOf(1, 2))
            queue.closed.get() shouldBe 1
        }

        test("a stop wakes a source blocked inside flatMapMerge, and closes it once (spec 0150)") { pekko ->
            val queue = Queue(1)
            val running = Stream.of(0).flatMapMerge(2) { blocking(queue) }.runCollect().start(pekko.system)
            queue.waiting.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true

            running.stop()

            running.exit.settled().shouldBeInstanceOf<Exit.Done<*>>()
            queue.closed.get() shouldBe 1
        }

        test("a take that is satisfied closes the resource it stopped reading") { pekko ->
            val queue = Queue(1, 2, 3)

            val exit = blocking(queue).take(2).runCollect().run(pekko.system).settled()
            exit shouldBe Exit.Done(listOf(1, 2))

            withClue(
                "Pekko closes a cancelled source after the sink completes; the exit waits for it, as on Forks",
            ) {
                queue.closed.get() shouldBe 1
            }
        }

        test("a close that throws ends a run that was otherwise Done as Died") { pekko ->
            val exit = Stream.blocking(
                open = { Unit },
                next = { _ -> null as Int? },
                wake = { },
                close = { _ -> error("the connection would not close") },
            ).runCollect().run(pekko.system).settled()

            exit.shouldBeInstanceOf<Exit.Died> { died -> died.cause.message shouldBe "the connection would not close" }
        }

        test(
            "reads that are ready reach the stream together, so a ready read is made before the one before it is seen",
        ) { pekko ->
            val firstSeen = CountDownLatch(1)
            val secondReadFirst = AtomicBoolean(false)
            val items = ArrayDeque(listOf(1, 2, 3))

            val exit = Stream.blocking(
                open = { items },
                next = { queue ->
                    queue.removeFirstOrNull()?.also { item ->
                        if (item == 2) secondReadFirst.set(!firstSeen.await(NOT_SEEN_MILLIS, TimeUnit.MILLISECONDS))
                    }
                },
                ready = { queue -> queue.isNotEmpty() },
                wake = { },
                close = { },
            ).map { item -> item.also { if (it == 1) firstSeen.countDown() } }.runCollect().run(pekko.system).settled()

            withClue(
                "the second read was made while the first was still in the source's batch, not handed over alone",
            ) {
                secondReadFirst.get() shouldBe true
            }

            exit shouldBe Exit.Done(listOf(1, 2, 3))
        }
    }
}
