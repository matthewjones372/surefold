package dev.surefold.stream

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldContainExactlyInAnyOrder
import dev.surefold.junit.withClue
import java.util.concurrent.CompletionStage

/** An element that is itself a stream, flattened in order and out of it. */
object FlattenSuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-flatten-test")

    private data class FetchError(val cursor: Int)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    private fun <E, A : Any> Stream<E, A>.collected(pekko: PekkoSystem): Exit<E, List<A>> =
        runCollect().run(pekko.system).settled()

    private fun page(cursor: Int): Stream<FetchError, String> =
        if (cursor == 2) Stream.from(listOf(1)).mapOrFail { fail(FetchError(cursor)) }
        else Stream.from(listOf("$cursor-a", "$cursor-b"))

    init {
        test("a stream of cursors becomes a stream of pages, in order") { pekko ->
            val pages = Stream.from(listOf(1, 3)).flatMapConcat { page(it) }.collected(pekko)

            withClue("no toSource and no Either in sight") {
                pages shouldBe Exit.Done(listOf("1-a", "1-b", "3-a", "3-b"))
            }
        }

        test("an inner failure is the outer stream's failure") { pekko ->
            Stream.from(listOf(1, 2, 3)).flatMapConcat { page(it) }.collected(pekko) shouldBe
                Exit.Failed(FetchError(2))
        }

        test("flatten is the same thing under the name a reader looks for") { pekko ->
            val nested: Stream<FetchError, Stream<FetchError, String>> =
                Stream.from(listOf(1, 3)).map { page(it) }

            nested.flatten().collected(pekko) shouldBe Exit.Done(listOf("1-a", "1-b", "3-a", "3-b"))
        }

        test("flatMapMerge answers every element, breadth deciding only the order") { pekko ->
            val merged = Stream.from(listOf(1, 3, 4)).flatMapMerge(4) { page(it) }.collected(pekko)

            merged.shouldBeInstanceOf<Exit.Done<List<String>>> { done ->
                done.value shouldContainExactlyInAnyOrder listOf("1-a", "1-b", "3-a", "3-b", "4-a", "4-b")
            }
        }

        test("a throw while building the inner stream dies naming the operator") { pekko ->
            Stream.from(listOf(1))
                .flatMapConcat<FetchError, FetchError, Int, String> { error("the fetch nobody declared") }
                .collected(pekko)
                .shouldBeInstanceOf<Exit.Died> { died ->
                    died.cause.suppressed.single().message.orEmpty() shouldContain "flatMapConcat died on 1"
                }
        }
    }
}
