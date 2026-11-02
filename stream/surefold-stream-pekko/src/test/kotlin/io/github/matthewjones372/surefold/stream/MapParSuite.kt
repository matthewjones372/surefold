package io.github.matthewjones372.surefold.stream

import dev.surefold.Resource
import dev.surefold.fail.Either
import dev.surefold.fail.bind
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldBeLessThan
import dev.surefold.junit.shouldBeSameInstanceAs
import dev.surefold.junit.withClue
import org.apache.pekko.stream.javadsl.Sink
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A blocking body per element, forked: what runs it, in what order, and what ends it. */
object MapParSuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-mappar-test")

    /** Long enough that a machine under load does not fail a claim about interruption. */
    private const val GENEROUS_SECONDS = 30L

    private const val SLOW_MILLIS = 500L

    private data class Declined(val id: Int)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    /** A ledger that declines the second id, so a body can both bind and fail. */
    private fun settle(id: Int): Either<Declined, String> =
        if (id == 2) Declined(id).left() else "receipt-$id".right()

    private fun paid(id: Int): Either<Declined, String> = "receipt-$id".right()

    init {
        test("every body runs at once, on a virtual thread, and the elements keep their order") { pekko ->
            val allFour = CountDownLatch(4)
            val virtual = ConcurrentLinkedQueue<Boolean>()

            val exit = Stream.from(listOf(1, 2, 3, 4))
                .mapParOrFail(4) { id ->
                    virtual.add(Thread.currentThread().isVirtual)
                    allFour.countDown()
                    // Bodies run one after another would never all reach this, so the claim of
                    // parallelism is made by the run answering at all rather than by a clock.
                    allFour.await(GENEROUS_SECONDS, TimeUnit.SECONDS)
                    paid(id).bind()
                }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Done(listOf("receipt-1", "receipt-2", "receipt-3", "receipt-4"))
            withClue("the default executor is surefold's, one virtual thread per body") {
                virtual.toList() shouldBe List(4) { true }
            }
        }

        test("a raise in one body fails the stream with its error and nothing after it is processed") { pekko ->
            val seen = ConcurrentLinkedQueue<String>()

            val exit = Stream.from(listOf(1, 2, 3, 4))
                .mapParOrFail(1) { id -> if (id == 2) fail(Declined(id)) else "receipt-$id" }
                .map { receipt ->
                    seen.add(receipt)
                    receipt
                }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Failed(Declined(2))
            withClue("the element before the failure is the only one that reached the next operator") {
                seen.toList() shouldBe listOf("receipt-1")
            }
        }

        test("a bind on a Left inside a body fails the stream with what the Left holds") { pekko ->
            val exit = Stream.from(listOf(1, 2, 3))
                .mapParOrFail(2) { id -> settle(id).bind() }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Failed(Declined(2))
        }

        test("a throw in a body arrives as Exit Died carrying the same throwable") { pekko ->
            val cause = IllegalStateException("no ledger")

            val exit = Stream.from(listOf(1, 2, 3))
                .mapParOrFail(2) { id -> if (id == 2) throw cause else paid(id).bind() }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit.shouldBeInstanceOf<Exit.Died> { died -> died.cause shouldBeSameInstanceAs cause }
        }

        test("on names the executor every body runs on") { pekko ->
            val threads = ConcurrentLinkedQueue<String>()
            val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ledger-1") }

            val exit = try {
                Stream.from(listOf(1, 2, 3))
                    .mapParOrFail(2) { id -> paid(id).bind() }
                    .mapPar(2, on = executor) { receipt ->
                        threads.add(Thread.currentThread().name)
                        receipt.uppercase()
                    }
                    .runCollect()
                    .run(pekko.system)
                    .settled()
            } finally {
                executor.shutdown()
            }

            exit shouldBe Exit.Done(listOf("RECEIPT-1", "RECEIPT-2", "RECEIPT-3"))
            withClue("a body runs where the call said it would, not on a virtual thread of surefold's") {
                threads.toList() shouldBe List(3) { "ledger-1" }
            }
        }

        test("parallelism of one runs the bodies one at a time") { pekko ->
            val inFlight = AtomicInteger()
            val most = ConcurrentLinkedQueue<Int>()

            val exit = Stream.from(listOf(1, 2, 3, 4))
                .mapParOrFail(1) { id ->
                    most.add(inFlight.incrementAndGet())
                    Thread.sleep(20)
                    inFlight.decrementAndGet()
                    paid(id).bind()
                }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Done(listOf("receipt-1", "receipt-2", "receipt-3", "receipt-4"))
            withClue("mapAsync's parallelism is mapPar's: one at a time means one body running") {
                most.toList().max() shouldBe 1
            }
        }

        // The failure a stream already declares is the one its bodies raise, so no second type appears.
        test("a failure declared upstream still arrives as Exit Failed through mapPar") { pekko ->
            val exit = Stream.from(listOf(1, 2, 3))
                .mapOrFail { id -> if (id == 3) fail(Declined(id)) else id }
                .mapPar(2) { id -> "receipt-$id" }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Failed(Declined(3))
        }

        // The one thing a `CompletionStage` cannot say on its own: the stream that asked for this element
        // has gone, so the thread the body is blocking on is interrupted rather than left running.
        test("a stream torn down interrupts a body still running, and the body sees it") { pekko ->
            val allFour = CountDownLatch(4)
            val never = CountDownLatch(1)
            val interrupted = CountDownLatch(3)

            val first = Stream.from(listOf(1, 2, 3, 4))
                .mapParOrFail(4) { id ->
                    allFour.countDown()
                    if (id == 1) {
                        allFour.await(GENEROUS_SECONDS, TimeUnit.SECONDS)
                    } else {
                        try {
                            never.await(GENEROUS_SECONDS, TimeUnit.SECONDS)
                        } catch (stop: InterruptedException) {
                            interrupted.countDown()
                        }
                    }
                    paid(id).bind()
                }
                .catchAll { Stream.empty() }
                .toSource()
                // Fewer elements than the run started bodies for: the take cancels upstream with three
                // of them still blocking.
                .take(1)
                .runWith(Sink.seq(), pekko.system)
                .toCompletableFuture()
                .join()

            first shouldBe listOf("receipt-1")
            withClue("every body still running when the stream ended was interrupted where it blocked") {
                interrupted.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true
            }
        }

        test("under mapParKeyed a slow element on one key does not hold back another key's results") { pekko ->
            val arrived = ConcurrentLinkedQueue<Int>()

            val exit = Stream.from(0..9)
                .mapParKeyed(8, key = { it % 2 }) { n ->
                    if (n == 0) Thread.sleep(SLOW_MILLIS)
                    n
                }
                .map { n -> n.also(arrived::add) }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit.shouldBeInstanceOf<Exit.Done<List<Int>>> { done -> done.value.sorted() shouldBe (0..9).toList() }
            withClue("every odd result arrived ahead of the even one that slept, and each key in order: $arrived") {
                arrived.filter { it % 2 == 1 } shouldBe listOf(1, 3, 5, 7, 9)
                arrived.filter { it % 2 == 0 } shouldBe listOf(0, 2, 4, 6, 8)
                arrived.indexOf(9) shouldBeLessThan arrived.indexOf(0)
            }
        }

        test("under mapParKeyed every key's results keep their order") { pekko ->
            val delays = List(400) { n -> (n * 7 % 4).toLong() }

            val exit = Stream.from(0 until 400)
                .mapParKeyed(16, key = { it % 7 }) { n -> n.also { Thread.sleep(delays[n]) } }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit.shouldBeInstanceOf<Exit.Done<List<Int>>> { done ->
                val results = done.value
                results.sorted() shouldBe (0 until 400).toList()
                results.groupBy { it % 7 } shouldBe (0 until 400).groupBy { it % 7 }
            }
        }

        test("a raise in a mapParKeyed body arrives as Exit Failed, not wrapped") { pekko ->
            val exit = Stream.from(listOf(1, 2, 3, 4))
                .mapParKeyed<Declined, Int, Int>(4, key = { it % 2 }) { id -> if (id == 3) fail(Declined(id)) else id }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Failed(Declined(3))
        }

        test("a throw in a mapParKeyed body arrives as Exit Died carrying the same throwable") { pekko ->
            val thrown = IllegalStateException("no ledger")

            val exit = Stream.from(listOf(1, 2))
                .mapParKeyed(2, key = { it }) { id -> if (id == 2) throw thrown else id }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit.shouldBeInstanceOf<Exit.Died> { died -> died.cause shouldBeSameInstanceAs thrown }
        }

        test("a throw in a later mapParKeyed body ends the run while an earlier one with its key still runs") { pekko ->
            val thrown = IllegalStateException("no ledger")
            val never = CountDownLatch(1)

            val exit = Stream.from(listOf(1, 2))
                .mapParKeyed(2, key = { "same" }) { id ->
                    if (id == 1) never.await(1, TimeUnit.MINUTES)
                    if (id == 2) throw thrown else id
                }
                .runCollect()
                .run(pekko.system)
                .toCompletableFuture()
                .get(GENEROUS_SECONDS, TimeUnit.SECONDS)

            exit.shouldBeInstanceOf<Exit.Died> { died -> died.cause shouldBeSameInstanceAs thrown }
        }
    }
}
