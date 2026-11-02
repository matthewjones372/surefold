package dev.surefold.stream

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeGreaterThanOrEqual
import dev.surefold.junit.withClue
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.stream.BoundedSourceQueue
import org.apache.pekko.stream.javadsl.Flow
import org.apache.pekko.stream.javadsl.Keep
import org.apache.pekko.stream.javadsl.Sink
import org.apache.pekko.stream.javadsl.Source
import java.util.concurrent.CompletionStage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.toJavaDuration

/** The way in from Pekko whatever a source materialises, and the ticker that needs no way in. */
object SourcesSuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-sources-test")

    private val fast: Duration = 10.milliseconds

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    /**
     * Three and no more. A ticker ends where the sink stops asking, so the count is on the sink side
     * rather than in an operator this library does not have.
     */
    private fun <A : Any> firstThree(): Sink<A, CompletionStage<List<A>>> =
        Flow.create<A>().take(3).toMat(Sink.seq(), Keep.right())

    init {
        test("tick is a stream of its own, with no materialised value for a caller to unwrap") { pekko ->
            val exit = Stream.tick(every = fast, element = "poll").runWith(firstThree()).run(pekko.system).settled()

            exit shouldBe Exit.Done(listOf("poll", "poll", "poll"))
        }

        test("tick waits the delay it names before the first element") { pekko ->
            val after = 300.milliseconds
            val started = System.nanoTime()

            val exit = Stream.tick(every = fast, element = "poll", after = after)
                .runWith(Sink.head<String>())
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Done("poll")
            withClue("the first element cannot arrive before the delay the call named") {
                (System.nanoTime() - started).nanoseconds shouldBeGreaterThanOrEqual after
            }
        }

        test("from takes a ticker, which materialises a Cancellable") { pekko ->
            val ticks: Source<String, Cancellable> = Source.tick(fast.toJavaDuration(), fast.toJavaDuration(), "poll")

            val exit = Stream.from(ticks).runWith(firstThree()).run(pekko.system).settled()

            exit shouldBe Exit.Done(listOf("poll", "poll", "poll"))
        }

        // Nothing can offer to a queue whose materialised value was dropped, so what the run claims is
        // that the source is taken and materialises: the element read comes from ahead of it.
        test("from takes a queue, which materialises the handle it is fed through") { pekko ->
            val queued: Source<Int, BoundedSourceQueue<Int>> = Source.queue(4)

            val exit = Stream.from(queued)
                .prepend(Stream.single(1))
                .runWith(Sink.head<Int>())
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Done(1)
        }

        test("a source that materialises NotUsed reads as it always did") { pekko ->
            val plain: Source<Int, NotUsed> = Source.from(listOf(1, 2, 3))

            val exit = Stream.from(plain).runCollect().run(pekko.system).settled()

            exit shouldBe Exit.Done(listOf(1, 2, 3))
        }
    }
}
