package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val ANSWER_SECONDS = 10L
private const val FOR_EVER_MILLIS = 60_000L

/** One pool thread, named, which every fork below is queued for. */
private fun <A> onOnePoolThread(block: (ExecutorService) -> A): A {
    val single = Executors.newFixedThreadPool(1) { runnable -> Thread(runnable, "surefold-help") }
    return try {
        block(single)
    } finally {
        single.shutdownNow()
    }
}

/** Spec 0037: a fork still waiting its turn on the executor runs on the thread that awaits it. */
class HelpTest {

    @Test
    fun `on a one-thread pool, a fork awaiting a fork queued behind it answers instead of waiting for ever`() {
        onOnePoolThread { single ->
            // The outer fork holds the pool's only thread; the inner one is queued behind it.
            val answer = CompletableFuture.supplyAsync {
                scope(on = single) {
                    val outer = async {
                        val inner = async { 21 }
                        inner.await() * 2
                    }
                    outer.await()
                }
            }

            answer.get(ANSWER_SECONDS, TimeUnit.SECONDS) shouldBe 42
        }
    }

    @Test
    fun `an interrupt sent to a fork run by the thread awaiting it ends with that fork`() {
        onOnePoolThread { single ->
            val answer = CompletableFuture.supplyAsync {
                scope(on = single) {
                    val outer = async {
                        val running = CountDownLatch(1)
                        val inner = async {
                            running.countDown()
                            Thread.sleep(FOR_EVER_MILLIS)
                        }
                        Thread.ofPlatform().start {
                            running.await()
                            inner.cancel()
                        }
                        val stopped = try {
                            inner.await()
                            false
                        } catch (_: InterruptedException) {
                            true
                        }
                        // The awaiting thread ran the body, and the cancel's interrupt went no further than it.
                        stopped && !Thread.currentThread().isInterrupted
                    }
                    outer.await()
                }
            }

            withClue("the inner fork was stopped, and its interrupt left the awaiting thread clear") {
                answer.get(ANSWER_SECONDS, TimeUnit.SECONDS) shouldBe true
            }
        }
    }
}
