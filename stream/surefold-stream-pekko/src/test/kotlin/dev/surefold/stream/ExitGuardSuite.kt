package dev.surefold.stream

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import dev.surefold.junit.withClue
import org.apache.pekko.stream.javadsl.Sink
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** The guard `Stream.fromStage` has, at the other end of the pipeline. */
object ExitGuardSuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-exit-guard-test")

    /** A sink whose materialised stage completes with null, whatever its type argument says. */
    @Suppress("UNCHECKED_CAST")
    private fun lying(): Sink<Int, CompletionStage<String>> =
        Sink.ignore<Int>().mapMaterializedValue {
            CompletableFuture.completedFuture<String?>(null) as CompletionStage<String>
        }

    init {
        test("a sink that materialises null is Died rather than Done of null") { pekko ->
            val exit = Stream.from(listOf(1, 2)).runWith(lying()).run(pekko.system).toCompletableFuture().join()

            exit.shouldBeInstanceOf<Exit.Died> { died ->
                withClue("Done(null) would hand a null into a slot Kotlin believes is not nullable") {
                    died.cause.shouldBeInstanceOf<NullPointerException>()
                    died.cause.message.orEmpty() shouldContain "run ended with null"
                }
            }
        }

        test("a sink that materialises a value is Done") { pekko ->
            val exit = Stream.from(listOf(1, 2)).runCollect().run(pekko.system).toCompletableFuture().join()

            exit shouldBe Exit.Done(listOf(1, 2))
        }
    }
}
