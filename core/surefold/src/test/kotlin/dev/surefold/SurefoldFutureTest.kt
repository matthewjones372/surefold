package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeSameInstanceAs
import dev.surefold.assert.shouldThrow
import dev.surefold.assert.withClue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.future.await as awaitSuspending
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class SurefoldFutureTest {

    @Test
    fun `the block runs on a virtual thread and its value completes the future`() {
        surefoldFuture { Thread.currentThread().isVirtual }.await() shouldBe true
    }

    @Test
    fun `a block that throws fails the future with that exception`() {
        val boom = Boom()

        shouldThrow<Boom> { surefoldFuture<Int> { throw boom }.await() } shouldBeSameInstanceAs boom
    }

    @Test
    fun `the block reads what the caller had bound`() {
        val tag = surefoldLocal { "unbound" }

        tag.locally("caller's") { surefoldFuture { tag.get() } }.await() shouldBe "caller's"
    }

    @Test
    fun `cancel interrupts the block, even without mayInterruptIfRunning, and stops its branches`() {
        val stuck = Stuck()
        val future = surefoldFuture { parZip({ stuck.forever() }, { stuck.forever() }) { _, _ -> } }
        stuck.started.await()

        future.cancel(false) shouldBe true

        withClue("cancel(false) is what kotlinx sends, so the interrupt cannot depend on the flag") {
            stuck.ended.await(5, TimeUnit.SECONDS) shouldBe true
        }
    }

    @Test
    fun `a cancelled coroutine awaiting it stops its branches`() {
        val stuck = Stuck()

        runBlocking {
            val caller =
                launch(Dispatchers.Default) {
                    surefoldFuture { parZip({ stuck.forever() }, { stuck.forever() }) { _, _ -> } }.awaitSuspending()
                }
            stuck.started.await()
            caller.cancelAndJoin()
        }

        stuck.ended.await(5, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun `a future derived from it is a plain one`() {
        val derived = surefoldFuture { 21 }.thenApply { it * 2 }

        derived.await() shouldBe 42
        withClue(
            "only the future surefoldFuture answers interrupts its block; thenApply's has no block of surefold's",
        ) {
            derived::class shouldBe java.util.concurrent.CompletableFuture::class
        }
    }
}

/** Two branches that block until interrupted, and say when both have started and when both have ended. */
private class Stuck {
    val started = CountDownLatch(2)
    val ended = CountDownLatch(2)

    fun forever() {
        try {
            started.countDown()
            CountDownLatch(1).await()
        } finally {
            ended.countDown()
        }
    }
}
