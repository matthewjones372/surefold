package io.github.matthewjones372.surefold.stream

import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.withClue
import dev.surefold.test.eventually
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val COUNT = 20_000
private const val GENEROUS_SECONDS = 30L
private const val PAUSE_NANOS = 20_000L
private const val PRODUCER_PAUSES_EVERY = 7
private const val READER_PAUSES_EVERY = 11

private fun <T> onVirtualThread(body: () -> T): CompletableFuture<T> {
    val answer = CompletableFuture<T>()
    Thread.ofVirtual().start {
        try {
            answer.complete(body())
        } catch (thrown: InterruptedException) {
            answer.completeExceptionally(thrown)
        }
    }
    return answer
}

private fun waitUntilParked(thread: Thread) =
    eventually(GENEROUS_SECONDS.seconds, every = 1.milliseconds) {
        check(thread.state == Thread.State.WAITING) { "$thread never parked" }
    }

/** What each ring's producer put, as `ring * COUNT + n`, split back by ring. */
private fun byRing(taken: List<Any?>, rings: Int) =
    List(rings) { ring -> taken.map { it as Int }.filter { it / COUNT == ring } }

/** Several producers, a ring each, and one reader taking from the rings in turn and parking on all of them. */
object InTurnSuite : Suite() {
    init {
        test("no wake-up is lost while every side keeps running dry and parking") {
            listOf(2, 3).forAll { rings ->
                listOf(1, 2, 16).forAll { capacity ->
                    val ring = Array(rings) { Ring(capacity) }
                    // Each side pauses now and then, so the reader finds every ring empty and parks, and each
                    // producer finds its ring full and parks, over and over.
                    ring.forEachIndexed { r, into ->
                        onVirtualThread {
                            repeat(COUNT) {
                                if (it % (PRODUCER_PAUSES_EVERY + r) == 0) LockSupport.parkNanos(PAUSE_NANOS)
                                into.put(r * COUNT + it)
                            }
                        }
                    }
                    val reading = InTurn(ring)
                    val taken = onVirtualThread {
                        List(rings * COUNT) {
                            if (it % READER_PAUSES_EVERY == 0) LockSupport.parkNanos(PAUSE_NANOS)
                            reading.next()
                        }
                    }
                    withClue("$rings rings of $capacity") {
                        byRing(taken.get(GENEROUS_SECONDS, TimeUnit.SECONDS), rings) shouldBe
                            List(rings) { r -> List(COUNT) { r * COUNT + it } }
                    }
                }
            }
        }

        test("a reader takes from each ring in turn while more than one has elements") {
            val rings = Array(2) { Ring(4) }
            repeat(4) { rings[0].put("a$it") }
            repeat(4) { rings[1].put("b$it") }
            val reading = InTurn(rings)

            List(8) { reading.next() } shouldBe listOf("a0", "b0", "a1", "b1", "a2", "b2", "a3", "b3")
        }

        test("a reader parked on every ring is woken by one element on whichever ring it comes") {
            (0..2).forAll { which ->
                val rings = Array(3) { Ring(16) }
                val reading = InTurn(rings)
                val taken = CompletableFuture<Any?>()
                val reader = Thread.ofVirtual().start { taken.complete(reading.next()) }
                waitUntilParked(reader)

                rings[which].put("only")

                withClue("ring $which") { taken.get(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe "only" }
            }
        }

        test("an interrupt reaches a reader parked on every ring") {
            val reading = InTurn(Array(2) { Ring(2) })
            // A bare thread, so it can be interrupted; it hands back what it threw, and the test checks it.
            val threw = CompletableFuture<Throwable>()
            val reader = Thread.ofVirtual().start {
                try {
                    val _ = reading.next()
                } catch (interrupted: InterruptedException) {
                    threw.complete(interrupted)
                }
            }
            waitUntilParked(reader)

            reader.interrupt()

            reader.join(TimeUnit.SECONDS.toMillis(GENEROUS_SECONDS))
            reader.isAlive shouldBe false
            threw.getNow(null).shouldBeInstanceOf<InterruptedException>()
        }
    }
}
