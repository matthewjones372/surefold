package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeSameInstanceAs
import dev.surefold.assert.shouldThrow
import dev.surefold.assert.withClue
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.left
import dev.surefold.fail.right
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import org.junit.jupiter.api.Test

class AwaitTest {

    @Test
    fun `a completed stage answers with its value`() {
        val stage: CompletionStage<Int> = CompletableFuture.completedFuture(42)

        stage.await() shouldBe 42
    }

    @Test
    fun `a CompletableFuture awaits as itself, with no ambiguity between its two supertypes`() {
        CompletableFuture.completedFuture(42).await() shouldBe 42
    }

    @Test
    fun `a plain Future answers with its value`() {
        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            val future: Future<Int> = pool.submit<Int> { 42 }

            future.await() shouldBe 42
        }
    }

    @Test
    fun `a stage that failed rethrows its cause, as the same instance`() {
        val boom = Boom()
        val failed = CompletableFuture<Int>()
        failed.completeExceptionally(boom)

        val thrown = shouldThrow<Boom> { failed.await() }

        withClue("the caller declared Boom, not the ExecutionException the wait wrapped it in") {
            thrown shouldBeSameInstanceAs boom
        }
    }

    @Test
    fun `a plain Future that failed rethrows its cause, as the same instance`() {
        val boom = Boom()
        val failed = FutureTask<Int> { throw boom }
        failed.run()

        shouldThrow<Boom> { (failed as Future<Int>).await() } shouldBeSameInstanceAs boom
    }

    @Test
    fun `an Either awaited inside either raises where it is bound`() {
        attempt<Bad, Int> {
            CompletableFuture.completedFuture(21.right()).await().bind() * 2
        } shouldBe 42.right()

        attempt<Bad, Int> {
            CompletableFuture.completedFuture(Bad("upstream").left()).await().bind()
        } shouldBe Bad("upstream").left()
    }

    @Test
    fun `a fork interrupted while awaiting cancels the stage, and its late value is dropped`() {
        val stage = CompletableFuture<String>()
        val awaiting = CountDownLatch(1)

        shouldThrow<InterruptedException> {
            attempt<Bad, String> {
                scope {
                    async {
                        awaiting.countDown()
                        stage.await()
                    }
                    awaiting.await()
                    "the scope closes with the fork still waiting"
                }
            }
        }

        stage.isCancelled shouldBe true
        withClue("a cancelled stage takes no value, so one arriving late is dropped") {
            stage.complete("late") shouldBe false
        }
    }

    @Test
    fun `a fork interrupted while awaiting a plain Future cancels it`() {
        val task = FutureTask<String> { "never run" }
        val awaiting = CountDownLatch(1)

        shouldThrow<InterruptedException> {
            attempt<Bad, String> {
                scope {
                    async {
                        awaiting.countDown()
                        (task as Future<String>).await()
                    }
                    awaiting.await()
                    "the scope closes with the fork still waiting"
                }
            }
        }

        task.isCancelled shouldBe true
    }
}
