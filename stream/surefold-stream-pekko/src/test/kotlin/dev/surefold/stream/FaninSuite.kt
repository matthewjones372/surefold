package dev.surefold.stream

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldContainExactlyInAnyOrder
import dev.surefold.junit.withClue
import java.util.concurrent.CompletionStage

/** Two feeds as one, and what their failure types do when they meet. */
object FaninSuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-fanin-test")

    private sealed interface FeedError

    private data class HttpDown(val why: String) : FeedError

    private data class KafkaLag(val behind: Int) : FeedError

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    private fun <E, A : Any> Stream<E, A>.collected(pekko: PekkoSystem): Exit<E, List<A>> =
        runCollect().run(pekko.system).settled()

    private val http: Stream<HttpDown, Int> = Stream.from(listOf(1, 2))

    private val kafka: Stream<KafkaLag, Int> = Stream.from(listOf(3, 4))

    init {
        test("two feeds that fail differently merge under the failure they share") { pekko ->
            val merged: Stream<FeedError, Int> = http.merge(kafka)

            withClue("no mapLeft on either side, and no Either in the element") {
                merged.collected(pekko).shouldBeInstanceOf<Exit.Done<List<Int>>> { done ->
                    done.value shouldContainExactlyInAnyOrder listOf(1, 2, 3, 4)
                }
            }
        }

        test("a failure on either side ends the run") { pekko ->
            val failing: Stream<KafkaLag, Int> = Stream.from(listOf(9)).mapOrFail { fail(KafkaLag(7)) }

            val merged: Stream<FeedError, Int> = http.merge(failing)

            merged.collected(pekko) shouldBe Exit.Failed(KafkaLag(7))
        }

        test("mergeAll takes as many as there are") { pekko ->
            val merged: Stream<FeedError, Int> = http.mergeAll(kafka, Stream.from(listOf(5)))

            merged.collected(pekko).shouldBeInstanceOf<Exit.Done<List<Int>>> { done ->
                done.value shouldContainExactlyInAnyOrder listOf(1, 2, 3, 4, 5)
            }
        }

        test("interleave takes a segment from each in turn") { pekko ->
            val interleaved: Stream<FeedError, Int> = http.interleave(kafka, 1)

            interleaved.collected(pekko) shouldBe Exit.Done(listOf(1, 3, 2, 4))
        }

        test("zip answers a Kotlin Pair, which destructures") { pekko ->
            val zipped: Stream<FeedError, String> = http.zip(kafka).map { (left, right) -> "$left-$right" }

            zipped.collected(pekko) shouldBe Exit.Done(listOf("1-3", "2-4"))
        }

        test("a throw in a zipWith body dies naming the operator") { pekko ->
            val zipped: Stream<FeedError, String> = http.zipWith(kafka) { _, _ -> error("the body nobody declared") }

            zipped.collected(pekko).shouldBeInstanceOf<Exit.Died> { died ->
                died.cause.suppressed.single().message.orEmpty() shouldContain "zipWith died on 3"
            }
        }
    }
}
