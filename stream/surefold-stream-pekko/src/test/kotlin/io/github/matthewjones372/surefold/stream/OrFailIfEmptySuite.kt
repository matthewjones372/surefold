package io.github.matthewjones372.surefold.stream

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import org.apache.pekko.stream.javadsl.Source
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** The word a caller says about a source surefold did not build, asserted on a real system. */
object OrFailIfEmptySuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-or-fail-if-empty-test")

    private data class Missing(val id: Int)

    private data class NoCustomer(val id: Int)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    init {
        test("a source that ends having emitted nothing fails with the error the caller named") { pekko ->
            val exit = Stream.from(Source.empty<String>())
                .orFailIfEmpty(Missing(7))
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Failed(Missing(7))
        }

        test("a source that emitted carries the elements it emitted and nothing else") { pekko ->
            val exit = Stream.from(Source.from(listOf("ada", "grace")))
                .orFailIfEmpty(Missing(7))
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Done(listOf("ada", "grace"))
        }

        test("a stream that fails before emitting fails with its own error rather than the emptiness one") { pekko ->
            val exit = Stream.from(listOf(1, 2, 3))
                .mapOrFail { id -> fail(NoCustomer(id)) }
                .orFailIfEmpty(Missing(7))
                .runCollect()
                .run(pekko.system)
                .settled()

            withClue("nothing was emitted, and the failure that stopped it is still the one to answer with") {
                exit shouldBe Exit.Failed(NoCustomer(1))
            }
        }

        test("a stream that fails after emitting is unchanged too") { pekko ->
            val exit = Stream.from(listOf(1, 2, 3))
                .mapOrFail { id -> if (id == 2) fail(NoCustomer(id)) else id }
                .orFailIfEmpty(Missing(7))
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Failed(NoCustomer(2))
        }

        // The case the spec is about, closed the other way: the stage completed with `null` and Pekko's
        // builder made an empty source of it, so the emptiness is what carries the name.
        test("a source built from a stage that completed with null fails with the error named") { pekko ->
            val completedWithNull = CompletableFuture<String>().apply { complete(null) }

            val exit = Stream.from(Source.completionStage(completedWithNull))
                .orFailIfEmpty(Missing(7))
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Failed(Missing(7))
        }
    }
}
