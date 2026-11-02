package dev.surefold.stream

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import dev.surefold.junit.withClue
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.javadsl.Sink
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.milliseconds

/** Batching, buffering, running state, and the taps beside a pipeline. */
object BatchingSuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-batching-test")

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    private fun <E, A : Any> Stream<E, A>.collected(pekko: PekkoSystem): Exit<E, List<A>> =
        runCollect().run(pekko.system).settled()

    private val ids = listOf(1, 2, 3, 4, 5)

    /** Declared so the step's types are known: a body that only throws infers nothing. */
    private fun failingStep(): Pair<Int, Int> = error("the step nobody declared")

    init {
        test("grouped answers batches, the last one short") { pekko ->
            Stream.from(ids).grouped(2).collected(pekko) shouldBe
                Exit.Done(listOf(listOf(1, 2), listOf(3, 4), listOf(5)))
        }

        test("sliding answers a window moved on by its step") { pekko ->
            Stream.from(ids).sliding(2, 2).collected(pekko) shouldBe
                Exit.Done(listOf(listOf(1, 2), listOf(3, 4), listOf(5)))
        }

        test("groupedWithin answers whatever it has when the window closes") { pekko ->
            val batched = Stream.from(ids).groupedWithin(10, 50.milliseconds).collected(pekko)

            withClue("ten never arrive, so the window is what ends the batch") {
                batched shouldBe Exit.Done(listOf(ids))
            }
        }

        test("a buffer leaves the elements alone") { pekko ->
            Stream.from(ids).buffer(2, OverflowStrategy.backpressure()).collected(pekko) shouldBe Exit.Done(ids)
        }

        test("scan emits the zero and then a total per element") { pekko ->
            Stream.from(ids).scan(0) { total, id -> total + id }.collected(pekko) shouldBe
                Exit.Done(listOf(0, 1, 3, 6, 10, 15))
        }

        test("a throw while carrying state dies naming scan and the element") { pekko ->
            Stream.from(ids)
                .scan(0) { _, _ -> error("the fold nobody declared") }
                .collected(pekko)
                .shouldBeInstanceOf<Exit.Died> { died ->
                    died.cause.suppressed.single().message.orEmpty() shouldContain "scan died on 1"
                }
        }

        test("statefulMap carries state and may owe one last element") { pekko ->
            val numbered = Stream.from(listOf("a", "b", "c"))
                .statefulMap({ 0 }, { seen, s -> (seen + 1) to "$seen:$s" }, { seen -> "seen $seen" })
                .collected(pekko)

            numbered shouldBe Exit.Done(listOf("0:a", "1:b", "2:c", "seen 3"))
        }

        test("a throw in statefulMap dies naming the element") { pekko ->
            Stream.from(ids)
                .statefulMap({ 0 }, { _, _ -> failingStep() })
                .collected(pekko)
                .shouldBeInstanceOf<Exit.Died> { died ->
                    died.cause.suppressed.single().message.orEmpty() shouldContain "statefulMap died on 1"
                }
        }

        test("alsoTo sees every element, and the pipeline is unchanged") { pekko ->
            val audited = ConcurrentLinkedQueue<Int>()

            val exit = Stream.from(ids).alsoTo(Sink.foreach { audited.add(it) }).collected(pekko)

            exit shouldBe Exit.Done(ids)
            audited.toList() shouldBe ids
        }

        test("wireTap leaves the pipeline alone") { pekko ->
            val exit = Stream.from(ids).wireTap(Sink.ignore()).collected(pekko)

            exit shouldBe Exit.Done(ids)
        }
    }
}
