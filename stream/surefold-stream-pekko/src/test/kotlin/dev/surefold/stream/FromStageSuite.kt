package dev.surefold.stream

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldBeSameInstanceAs
import dev.surefold.junit.shouldContain
import dev.surefold.junit.withClue
import org.apache.pekko.stream.javadsl.Source
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** What a stage becomes when it starts a stream, and what a `null` completion becomes with it. */
object FromStageSuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-from-stage-test")

    private data class Missing(val id: Int)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    /**
     * A stage whose type says `String` and whose value is `null`, which is what a Java client hands
     * Kotlin: the platform type lets the completion through and the compiler never sees it.
     */
    private fun completedWithNull(): CompletableFuture<String> =
        CompletableFuture<String>().apply { complete(null) }

    init {
        test("a completed stage is the one element the stream carries") { pekko ->
            val exit = Stream.fromStage(CompletableFuture.completedFuture("ada"))
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Done(listOf("ada"))
        }

        test("a stage that completes with null dies with a NullPointerException naming the builder") { pekko ->
            val exit = Stream.fromStage(completedWithNull()).runCollect().run(pekko.system).settled()

            withClue("Pekko would have answered Done with no elements at all") {
                exit.shouldBeInstanceOf<Exit.Died> { died ->
                    died.cause.shouldBeInstanceOf<NullPointerException>()
                    died.cause.message shouldContain "Stream.fromStage"
                    withClue("the message has to say what happened, not only where") {
                        died.cause.message shouldContain "completed with null"
                    }
                }
            }
        }

        test("a stage that completes with null fails with the error the caller named for it") { pekko ->
            val exit = Stream.fromStage(completedWithNull(), ifNull = Missing(7))
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Failed(Missing(7))
        }

        test("a stage that completes with a value is one element in the form that names an error too") { pekko ->
            val exit = Stream.fromStage(CompletableFuture.completedFuture("ada"), ifNull = Missing(7))
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Done(listOf("ada"))
        }

        test("a stage that fails dies with the throwable that failed it and no wrapper") { pekko ->
            val cause = IllegalStateException("no directory")

            val exit = Stream.fromStage(CompletableFuture.failedFuture<String>(cause))
                .runCollect()
                .run(pekko.system)
                .settled()

            exit.shouldBeInstanceOf<Exit.Died> { died ->
                withClue("what the caller declared and catches is the cause, as it is through await") {
                    died.cause shouldBeSameInstanceAs cause
                }
            }
        }

        test("a stage completed after the run started still delivers its element") { pekko ->
            val late = CompletableFuture<String>()

            val running = Stream.fromStage(late).runCollect().run(pekko.system)

            withClue("nothing can have arrived while the stage is still outstanding") {
                running.toCompletableFuture().isDone shouldBe false
            }
            val completing = Thread.ofVirtual().start { late.complete("grace") }
            completing.join()

            running.settled() shouldBe Exit.Done(listOf("grace"))
        }

        // The behaviour the builder exists for, asserted rather than described: Pekko's own builder over
        // the same stage answers `Done` with nothing in it, and a run that processed no rows looks exactly
        // like a run that had none to process.
        test("Pekko's own builder over the same stage runs to Done with no elements") { pekko ->
            val exit = Stream.from(Source.completionStage(completedWithNull()))
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Done(emptyList())
        }
    }
}
