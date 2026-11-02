package dev.surefold.stream

import dev.surefold.Resource
import dev.surefold.fail.attempt
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeSameInstanceAs
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withClue
import dev.surefold.scope
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch

/** The one fold of an `Exit` into a `Raise`: what each case does to the scope waiting on it. */
object AwaitExitSuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-awaitexit-test")

    private data class Declined(val id: Int)

    private fun completed(exit: Exit<Declined, Int>): CompletableFuture<Exit<Declined, Int>> =
        CompletableFuture.completedFuture(exit)

    init {
        test("Done is the value the run folded to") { _ ->
            attempt { awaitExit(completed(Exit.Done(7))) } shouldBe 7.right()
        }

        test("Failed raises the error the run ended with") { _ ->
            attempt { awaitExit(completed(Exit.Failed(Declined(2)))) } shouldBe Declined(2).left()
        }

        test("Died throws the cause nobody declared") { _ ->
            val cause = IllegalStateException("no ledger")

            shouldThrow<IllegalStateException>({
                attempt { awaitExit(completed(Exit.Died(cause))) }
            }) { thrown ->
                withClue("a defect is not a failure a Raise can name, so it stays a throw") {
                    thrown shouldBeSameInstanceAs cause
                }
            }
        }

        // The pipeline the spec draws: a run, folded where the handler that started it can read it.
        test("a run awaited in a Raise gives back what it folded") { pekko ->
            val counted = attempt<Declined, Int> {
                awaitExit(
                    Stream.from(listOf(1, 2, 3))
                        .map { id -> "receipt-$id" }
                        .runFold(0) { n, _ -> n + 1 }
                        .run(pekko.system),
                )
            }

            counted shouldBe 3.right()
        }

        test("a declared failure in the run raises where the run was awaited") { pekko ->
            val counted = attempt<Declined, Int> {
                awaitExit(
                    Stream.from(listOf(1, 2, 3))
                        .mapParOrFail<Declined, Int, String>(2) { id ->
                            if (id == 2) fail(Declined(id)) else "receipt-$id"
                        }
                        .runFold(0) { n, _ -> n + 1 }
                        .run(pekko.system),
                )
            }

            counted shouldBe Declined(2).left()
        }

        // The wait is `surefold-pekko`'s, so the bridge that cancels an abandoned stage is the same code.
        test("a fork interrupted while awaiting a run cancels the stage") { _ ->
            val stage = CompletableFuture<Exit<Declined, Int>>()
            val awaiting = CountDownLatch(1)

            shouldThrow<InterruptedException>({
                attempt<Declined, String> {
                    scope {
                        async {
                            awaiting.countDown()
                            awaitExit(stage)
                        }
                        awaiting.await()
                        "the scope closes with the fork still waiting"
                    }
                }
            })

            stage.isCancelled shouldBe true
            withClue("a cancelled stage takes no value, so a run answering late is dropped") {
                stage.complete(Exit.Done(1)) shouldBe false
            }
        }
    }
}
