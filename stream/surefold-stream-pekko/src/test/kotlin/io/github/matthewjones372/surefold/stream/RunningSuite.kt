package io.github.matthewjones372.surefold.stream

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import org.apache.pekko.Done
import org.apache.pekko.stream.javadsl.Sink
import org.apache.pekko.stream.javadsl.Source
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.hours

/** A run whoever started it can end: `start` answers the exit `run` does, and a way to stop it. */
object RunningSuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-running-test")

    private const val GENEROUS_SECONDS = 30L

    private data class Declined(val id: Int)

    /** Bounded, so a stop that did nothing fails the test rather than hanging it. */
    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS)

    /** Three elements and then an upstream that never ends, which is the shape a feed has. */
    private fun feed(seen: CountDownLatch): Stream<Nothing, Int> =
        Stream.from(listOf(1, 2, 3))
            .concat(Stream.from(Source.never()))
            .alsoTo(Sink.foreach { _ -> seen.countDown() })

    init {
        test("a tick stream stopped ends Done without waiting for its next tick") { pekko ->
            val first = CountDownLatch(1)
            val running = Stream.tick(every = 1.hours, element = Unit, after = ZERO)
                .runWith(Sink.foreach { _ -> first.countDown() })
                .start(pekko.system)
            first.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true

            running.stop()

            withClue("the next tick is an hour away, so an answer at all is the stop working") {
                running.exit.settled() shouldBe Exit.Done(Done.getInstance())
            }
        }

        test("a collecting run stopped answers what arrived before the stop") { pekko ->
            val allThree = CountDownLatch(3)
            val running = feed(allThree).runCollect().start(pekko.system)
            allThree.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true

            running.stop()

            running.exit.settled() shouldBe Exit.Done(listOf(1, 2, 3))
        }

        test("close returns only once the exit has completed") { pekko ->
            val allThree = CountDownLatch(3)
            val running = feed(allThree).runCollect().start(pekko.system)
            allThree.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true

            running.close()

            withClue("a release that returned before the run ended would close what the run still uses") {
                running.exit.toCompletableFuture().isDone shouldBe true
            }
        }

        test("stopping a run that already ended changes nothing") { pekko ->
            val running = Stream.from(listOf(1)).runCollect().start(pekko.system)
            running.exit.settled() shouldBe Exit.Done(listOf(1))

            running.stop()
            running.close()

            running.exit.settled() shouldBe Exit.Done(listOf(1))
        }

        test("a started run answers a declared failure as run does") { pekko ->
            val running = Stream.fail(Declined(1)).runCollect().start(pekko.system)

            running.exit.settled() shouldBe Exit.Failed(Declined(1))
        }
    }
}
