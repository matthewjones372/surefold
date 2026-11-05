package dev.surefold.stream

import dev.surefold.VirtualThreads
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldBeSameInstanceAs
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withClue
import dev.surefold.test.eventually
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val GENEROUS_SECONDS = 30L
private const val PAUSE_NANOS = 20_000L
private const val STOPPING_MILLIS = 50L
private const val BODY_PAUSES_EVERY = 7
private const val PULL_PAUSES_EVERY = 11
private const val ROUNDS = 2_000

private fun waitUntilParked(thread: Thread) =
    eventually(GENEROUS_SECONDS.seconds, every = 1.milliseconds) {
        check(thread.state == Thread.State.WAITING) { "$thread never parked" }
    }

// The catch is as wide as the body: whatever it threw is what the test reads from the future.
@Suppress("TooGenericExceptionCaught")
private fun <T> onVirtualThread(body: () -> T): CompletableFuture<T> {
    val answer = CompletableFuture<T>()
    Thread.ofVirtual().start {
        try {
            answer.complete(body())
        } catch (thrown: Throwable) {
            answer.completeExceptionally(thrown)
        }
    }
    return answer
}

/** One body on its own thread, waited on by the pull and let go of by the run. */
object InFlightSuite : Suite() {
    init {
        test("a pull parked on a body is woken by its answer") {
            val release = CountDownLatch(1)
            val flight =
                InFlight(VirtualThreads) {
                    release.await()
                    "answer"
                }
            val awaited = CompletableFuture<String>()
            val puller = Thread.ofVirtual().start { awaited.complete(flight.await()) }
            waitUntilParked(puller)

            release.countDown()

            awaited.get(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe "answer"
        }

        test("no wake is lost while answers race the pulls waiting for them") {
            val count = 20_000
            val answers = onVirtualThread {
                List(count) { i ->
                    // Some bodies finish before the pull looks, some while it yields, some once it has parked.
                    val flight =
                        InFlight(VirtualThreads) {
                            if (i % BODY_PAUSES_EVERY == 0) LockSupport.parkNanos(PAUSE_NANOS)
                            i
                        }
                    if (i % PULL_PAUSES_EVERY == 0) LockSupport.parkNanos(PAUSE_NANOS)
                    flight.await()
                }
            }

            answers.get(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe List(count) { it }
        }

        test("whenDone runs once, whether the body answered before it was asked or after") {
            (0 until ROUNDS).forAll { round ->
                val ran = AtomicInteger()
                val done = CountDownLatch(1)
                val flight = InFlight(VirtualThreads) { if (round % 2 == 0) LockSupport.parkNanos(PAUSE_NANOS) }
                if (round % 3 == 0) flight.await()
                flight.whenDone {
                    ran.incrementAndGet()
                    done.countDown()
                }
                done.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true
                flight.await()
                withClue("round $round") { ran.get() shouldBe 1 }
            }
        }

        test("what the body threw is what await throws, as it threw it") {
            val failure = IllegalStateException("the body failed")
            val flight = InFlight<Unit>(VirtualThreads) { throw failure }

            shouldThrow<IllegalStateException>({ flight.await() }) { thrown -> thrown shouldBeSameInstanceAs failure }
        }

        test("an interrupt reaches a pull parked on a body that has not answered") {
            val flight = InFlight(VirtualThreads) { Thread.sleep(Long.MAX_VALUE) }
            val outcome = CompletableFuture<Throwable?>()
            val puller =
                Thread.ofVirtual().start {
                    try {
                        flight.await()
                        outcome.complete(null)
                    } catch (thrown: InterruptedException) {
                        outcome.complete(thrown)
                    }
                }
            waitUntilParked(puller)

            puller.interrupt()

            outcome.get(GENEROUS_SECONDS, TimeUnit.SECONDS).shouldBeInstanceOf<InterruptedException>()
            flight.cancel()
        }

        test("cancel interrupts a running body and returns only once it has stopped") {
            val started = CountDownLatch(1)
            val stopped = AtomicInteger()
            val flight =
                InFlight(VirtualThreads) {
                    started.countDown()
                    try {
                        Thread.sleep(Long.MAX_VALUE)
                    } finally {
                        // Still running after the interrupt: cancel must wait for this.
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(STOPPING_MILLIS))
                        stopped.incrementAndGet()
                    }
                }
            started.await()

            flight.cancel()

            stopped.get() shouldBe 1
            shouldThrow<InterruptedException> { flight.await() }
        }

        test("a body cancelled before it started never runs, and await says it was cancelled") {
            val queued = mutableListOf<Runnable>()
            val held = Executor { queued.add(it) }
            val ran = AtomicInteger()
            val flight = InFlight(held) { ran.incrementAndGet() }
            val canceller = Thread.ofVirtual().start { flight.cancel() }
            waitUntilParked(canceller)

            queued.single().run()

            canceller.join(TimeUnit.SECONDS.toMillis(GENEROUS_SECONDS))
            canceller.isAlive shouldBe false
            ran.get() shouldBe 0
            shouldThrow<CancellationException> { flight.await() }
        }

        test("a cancel interrupted while it waits still waits, and keeps the interrupt") {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val flight =
                InFlight(VirtualThreads) {
                    started.countDown()
                    try {
                        Thread.sleep(Long.MAX_VALUE)
                    } catch (_: InterruptedException) {
                        release.await()
                    }
                }
            started.await()
            val keptInterrupt = CompletableFuture<Boolean>()
            val canceller =
                Thread.ofVirtual().start {
                    flight.cancel()
                    keptInterrupt.complete(Thread.currentThread().isInterrupted)
                }
            waitUntilParked(canceller)
            canceller.interrupt()
            Thread.sleep(STOPPING_MILLIS)
            keptInterrupt.isDone shouldBe false

            release.countDown()

            keptInterrupt.get(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true
        }
    }
}
