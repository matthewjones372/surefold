package io.github.matthewjones372.surefold.stream

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeLessThanOrEqual
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withClue
import org.apache.pekko.actor.ActorSystem
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A stream pulled as a `Sequence`, on every backend that runs apart from its caller. `TestStreams` runs a run on
 * the thread that starts it, so a buffer that fills would hold the reader's own thread.
 */
object SequenceSuite : SharedSuite<ActorSystem>() {
    override val shared: Resource<ActorSystem> = actorSystem("surefold-stream-sequence-test")

    private const val BUFFER = 4

    /** How long a gate waits before the test fails: a guard against a hang, not a claim about speed. */
    private const val GUARD_SECONDS = 10L

    private val backends: List<Backend> = listOf(OnPekko, OnForks, OnActors)

    /** Registers [name] once for each backend, named for it, with that backend made fresh for the test. */
    private fun each(name: String, check: (StreamBackend) -> Unit) =
        backends.forEach { backend -> test("$name [${backend.name}]") { system -> check(backend.on(system)) } }

    private val naturals: Iterable<Int> = generateSequence(1) { it + 1 }.asIterable()

    /** Holds if the gate opens before the guard runs out, and only then goes on to [then]. */
    private fun CountDownLatch.opens(then: () -> Unit) {
        val opened = await(GUARD_SECONDS, TimeUnit.SECONDS)
        withClue("the gate never opened") { opened shouldBe true }
        if (opened) then()
    }

    init {
        each("a reader pulls every element, in order") { backend ->
            Stream.from(1..100).toSequence(backend, BUFFER).toList() shouldBe (1..100).toList()
        }

        each("a stalled reader holds the run at the buffer, and a reading one keeps it there") { backend ->
            val pulled = AtomicInteger()
            val read = AtomicInteger()
            val furthestAhead = AtomicInteger()
            val full = CountDownLatch(1)
            val pulling = Stream.from(naturals).map { n ->
                val count = pulled.incrementAndGet()
                furthestAhead.accumulateAndGet(count - read.get(), ::maxOf)
                if (count == BUFFER + 1) full.countDown()
                n
            }

            val elements = pulling.toSequence(backend, BUFFER).iterator()
            full.opens {
                withClue("with nothing read, the buffer is full and one more element waits for room") {
                    pulled.get() shouldBe BUFFER + 1
                }
                repeat(BUFFER * 10) {
                    val _ = elements.next()
                    read.incrementAndGet()
                }
                // Read before the close, which lets upstream run free until the stop reaches it.
                val ahead = furthestAhead.get()
                (elements as AutoCloseable).close()

                // One past buffer and the waiting element: a slot frees as an element is taken, before `read`
                // counts it.
                withClue("upstream got $ahead ahead of the reader") { ahead shouldBeLessThanOrEqual BUFFER + 2 }
            }
        }

        each("closing mid-stream from another thread releases a full buffer and stops the run") { backend ->
            val full = CountDownLatch(1)
            val released = CountDownLatch(1)
            val counted = AtomicInteger()
            val endless = Stream.blocking(
                open = { counted },
                // One read, then the buffer's worth, then one more that waits for room.
                next = { it.incrementAndGet().also { n -> if (n == BUFFER + 2) full.countDown() } },
                wake = { },
                close = { released.countDown() },
            )

            val elements = endless.toSequence(backend, BUFFER).iterator()
            elements.next() shouldBe 1
            full.opens {
                CompletableFuture.runAsync { (elements as AutoCloseable).close() }.get(GUARD_SECONDS, TimeUnit.SECONDS)

                withClue("the run's source was closed by the time close returned") { released.count shouldBe 0L }
                elements.hasNext() shouldBe false
            }
        }

        each("closing wakes a reader waiting on an empty buffer, and it sees the end") { backend ->
            val asked = CountDownLatch(1)
            val never = LinkedBlockingQueue<Int>()
            val silent = Stream.blocking(
                open = { never },
                next = { queue ->
                    asked.countDown()
                    queue.take().takeIf { it >= 0 }
                },
                wake = { queue -> queue.put(-1) },
                close = { },
            )
            val elements = silent.toSequence(backend, BUFFER).iterator()
            val waiting = CompletableFuture.supplyAsync { elements.hasNext() }
            asked.opens {
                (elements as AutoCloseable).close()

                waiting.get(GUARD_SECONDS, TimeUnit.SECONDS) shouldBe false
            }
        }

        each("a run that dies throws its cause from the reader's next hasNext, after what it sent") { backend ->
            val dying = Stream.of(1, 2, 3).map { n -> if (n == 3) error("third element refused") else n }
            val elements = dying.toSequence(backend, BUFFER).iterator()

            elements.next() shouldBe 1
            elements.next() shouldBe 2
            shouldThrow<IllegalStateException>({ elements.hasNext() }) {
                it.message shouldContain
                    "third element refused"
            }
        }

        each("a second iteration throws") { backend ->
            val once = Stream.of(1, 2).toSequence(backend, BUFFER)
            once.toList() shouldBe listOf(1, 2)

            shouldThrow<IllegalStateException> { once.iterator() }
        }

        test("a buffer with no room is refused") { _ ->
            shouldThrow<IllegalArgumentException>({ Stream.of(1).toSequence(Forks(), buffer = 0) }) {
                it.message shouldContain "at least one element"
            }
        }
    }
}
