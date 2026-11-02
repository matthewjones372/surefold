package dev.surefold.stream

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import dev.surefold.junit.withClue
import java.util.concurrent.CompletionStage

/** What the bounding operators promise, asserted on a real system. */
object BoundsSuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-bounds-test")

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    private fun <E, A : Any> Stream<E, A>.collected(pekko: PekkoSystem): Exit<E, List<A>> =
        runCollect().run(pekko.system).settled()

    private val ids = listOf(1, 2, 3, 4, 5)

    init {
        test("take ends the stream after the count it was given") { pekko ->
            Stream.from(ids).take(2).collected(pekko) shouldBe Exit.Done(listOf(1, 2))
        }

        test("drop begins the stream after the count it was given") { pekko ->
            Stream.from(ids).drop(3).collected(pekko) shouldBe Exit.Done(listOf(4, 5))
        }

        test("takeWhile ends where the predicate first answers false") { pekko ->
            Stream.from(ids).takeWhile { it < 3 }.collected(pekko) shouldBe Exit.Done(listOf(1, 2))
        }

        test("dropWhile begins where the predicate first answers false, that element included") { pekko ->
            Stream.from(ids).dropWhile { it < 3 }.collected(pekko) shouldBe Exit.Done(listOf(3, 4, 5))
        }

        test("filterNot keeps what filter would have dropped") { pekko ->
            Stream.from(ids).filterNot { it % 2 == 0 }.collected(pekko) shouldBe Exit.Done(listOf(1, 3, 5))
        }

        test("a bound is a pipe as well as a stream") { pekko ->
            val firstTwo: Pipe<Nothing, Int, Int> = Pipe.take(2)

            Stream.from(ids).via(firstTwo).collected(pekko) shouldBe Exit.Done(listOf(1, 2))
        }

        test("a throw in a predicate dies naming the operator") { pekko ->
            Stream.from(ids)
                .takeWhile { error("the predicate nobody declared") }
                .collected(pekko)
                .shouldBeInstanceOf<Exit.Died> { died ->
                    withClue("the operator and the element are what a reader needs to find it") {
                        died.cause.suppressed.single().message.orEmpty() shouldContain "takeWhile died on 1"
                    }
                }
        }
    }
}
