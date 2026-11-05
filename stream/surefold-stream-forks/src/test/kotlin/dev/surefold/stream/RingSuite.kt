package dev.surefold.stream

import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldBeNull
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withClue
import dev.surefold.test.eventually
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val GENEROUS_SECONDS = 30L
private const val PAUSE_NANOS = 20_000L
private const val PRODUCER_PAUSES_EVERY = 7
private const val CONSUMER_PAUSES_EVERY = 11

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

/** One producer and one consumer across a ring that wakes either side only when it has to. */
object RingSuite : Suite() {
    init {
        test("every element arrives once, in order, whatever the capacity") {
            listOf(1, 3, 4, 256).forAll { capacity ->
                val ring = Ring(capacity)
                val count = 100_000
                onVirtualThread { repeat(count) { ring.put(it) } }
                val taken = onVirtualThread { List(count) { ring.take() } }.get(GENEROUS_SECONDS, TimeUnit.SECONDS)
                withClue("capacity $capacity") { taken shouldBe List(count) { it } }
            }
        }

        test("a producer runs ahead by the capacity and no further") {
            listOf(3, 4, 5).forAll { capacity ->
                val ring = Ring(capacity)
                val put = AtomicInteger()
                val producer =
                    Thread.ofVirtual().start {
                        repeat(10) {
                            ring.put(it)
                            put.incrementAndGet()
                        }
                    }
                waitUntilParked(producer)

                withClue("capacity $capacity") { put.get() shouldBe capacity }
                List(10) { ring.take() } shouldBe List(10) { it }
            }
        }

        test("a consumer parked on an empty ring is woken by one element") {
            val ring = Ring(256)
            val taken = onVirtualThread { ring.take() }
            Thread.sleep(50)

            ring.put("only")

            taken.get(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe "only"
        }

        test("a producer parked on a full ring is woken once the consumer has made room") {
            val ring = Ring(8)
            val produced = onVirtualThread { repeat(100) { ring.put(it) } }
            val taken = List(100) { ring.take() }

            produced.get(GENEROUS_SECONDS, TimeUnit.SECONDS)
            taken shouldBe List(100) { it }
        }

        test("no wake-up is lost while each side keeps running dry and parking") {
            listOf(1, 2, 64).forAll { capacity ->
                val ring = Ring(capacity)
                val count = 20_000
                // Each side pauses now and then, so the other finds the ring empty or full and parks, over and over.
                onVirtualThread {
                    repeat(count) {
                        if (it % PRODUCER_PAUSES_EVERY == 0) LockSupport.parkNanos(PAUSE_NANOS)
                        ring.put(it)
                    }
                }
                val taken = onVirtualThread {
                    List(count) {
                        if (it % CONSUMER_PAUSES_EVERY == 0) LockSupport.parkNanos(PAUSE_NANOS)
                        ring.take()
                    }
                }
                withClue("capacity $capacity") {
                    taken.get(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe List(count) { it }
                }
            }
        }

        test("an interrupt reaches a producer parked on a full ring") {
            val ring = Ring(2)
            val outcome = CompletableFuture<Throwable?>()
            val producer =
                Thread.ofVirtual().start {
                    try {
                        repeat(3) { ring.put(it) }
                        outcome.complete(null)
                    } catch (thrown: InterruptedException) {
                        outcome.complete(thrown)
                    }
                }
            waitUntilParked(producer)

            producer.interrupt()

            outcome.get(GENEROUS_SECONDS, TimeUnit.SECONDS).shouldBeInstanceOf<InterruptedException>()
        }

        test("an interrupt reaches a consumer parked on an empty ring") {
            val ring = Ring(2)
            // A bare thread, so it can be interrupted; it hands back what it threw, and the test checks it.
            val threw = CompletableFuture<Throwable>()
            val consumer =
                Thread.ofVirtual().start {
                    try {
                        val _ = ring.take()
                    } catch (interrupted: InterruptedException) {
                        threw.complete(interrupted)
                    }
                }
            waitUntilParked(consumer)

            consumer.interrupt()

            consumer.join(TimeUnit.SECONDS.toMillis(GENEROUS_SECONDS))
            consumer.isAlive shouldBe false
            threw.getNow(null).shouldBeInstanceOf<InterruptedException>()
        }

        test("an interrupted producer stops at its next put even when there is room") {
            val ring = Ring(256)
            Thread.currentThread().interrupt()

            shouldThrow<InterruptedException> { ring.put(1) }
            Thread.interrupted() shouldBe false
            ring.poll().shouldBeNull()
        }
    }
}
