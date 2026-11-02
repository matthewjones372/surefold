package io.github.matthewjones372.surefold.stream

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import org.apache.pekko.stream.javadsl.Source
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A source built through the seam hears the run stop and end, so it can finish what it started. */
@OptIn(SourceSeam::class)
object HookedSuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-hooked-test")

    private const val GENEROUS_SECONDS = 30L

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS)

    /** Three elements, then open until the run's stop completes it, as a consumer that stops fetching does. */
    private fun draining(ended: CountDownLatch, sent: CountDownLatch): Stream<Nothing, Int> =
        Stream.hooked { hooks ->
            Source.from(listOf(1, 2, 3))
                .concatMat(Source.maybe<Int>()) { _, open -> open }
                .mapMaterializedValue { open: CompletableFuture<Optional<Int>> ->
                    hooks.onStop { open.complete(Optional.empty()) }
                    hooks.onEnd { ended.countDown() }
                }
                .map { n -> n.also { sent.countDown() } }
        }

    init {
        test("a stop is the source's own drain, so everything it sent reaches the sink") { pekko ->
            val ended = CountDownLatch(1)
            val sent = CountDownLatch(3)
            val running = draining(ended, sent).runCollect().start(pekko.system)
            sent.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true

            running.stop()

            running.exit.settled() shouldBe Exit.Done(listOf(1, 2, 3))
            withClue("the end hook runs once the exit has completed") {
                ended.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true
            }
        }

        test("a run nobody stops still tells its sources it has ended") { pekko ->
            val ended = CountDownLatch(1)
            val exit = Stream
                .hooked { hooks -> Source.single(1).mapMaterializedValue { hooks.onEnd { ended.countDown() } } }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Done(listOf(1))
            ended.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true
        }

        test("a drain registered after the stop runs at once") { _ ->
            val hooks = RunHooks()
            hooks.stop() shouldBe false

            val drained = CountDownLatch(1)
            hooks.onStop { drained.countDown() }

            drained.count shouldBe 0L
        }
    }
}
