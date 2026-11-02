package io.github.matthewjones372.surefold.stream

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import dev.surefold.junit.withClue
import java.util.concurrent.CompletionStage

/** A declared failure said again in another vocabulary, without saying recovery. */
object MapErrorSuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-map-error-test")

    private data class Declined(val id: Int)

    private data class IngestError(val why: String)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    private fun <E, A : Any> Stream<E, A>.collected(pekko: PekkoSystem): Exit<E, List<A>> =
        runCollect().run(pekko.system).settled()

    private fun declining(): Stream<Declined, Int> =
        Stream.from(listOf(1, 2, 3)).mapOrFail { id -> if (id == 2) fail(Declined(id)) else id }

    init {
        test("a failure leaves as the error it was mapped to") { pekko ->
            val exit = declining().mapError { IngestError("declined ${it.id}") }.collected(pekko)

            exit shouldBe Exit.Failed(IngestError("declined 2"))
        }

        test("a stream that does not fail is untouched") { pekko ->
            val exit = Stream.from(listOf(1, 2)).mapError { IngestError("never") }.collected(pekko)

            exit shouldBe Exit.Done(listOf(1, 2))
        }

        test("a throw while mapping the failure dies naming the operator") { pekko ->
            declining()
                .mapError<Declined, IngestError, Int> { error("the mapping nobody declared") }
                .collected(pekko)
                .shouldBeInstanceOf<Exit.Died> { died ->
                    withClue("a defect in the mapping is not the failure it was mapping") {
                        died.cause.suppressed.single().message.orEmpty() shouldContain "mapError died on Declined(id=2)"
                    }
                }
        }
    }
}
