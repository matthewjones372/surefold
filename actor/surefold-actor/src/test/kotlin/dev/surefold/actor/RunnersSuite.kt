package dev.surefold.actor

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeLessThan
import dev.surefold.junit.shouldBeLessThanOrEqual
import dev.surefold.junit.withClue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue

private const val SPACED = 2_000
private const val GAP_NANOS = 50_000L
private const val QUARTER_SPIN_NANOS = 2_500L

object RunnersSuite : Suite() {
    init {
        // Spec 0104: an urgent activation is taken before every ordinary one already waiting.
        test("an urgent activation runs ahead of the ordinary ones queued before it") {
            val runners = Runners(parallelism = 1, cap = 1)
            val release = CountDownLatch(1)
            val ran = LinkedBlockingQueue<String>()
            // The only runner is held, so everything submitted after this waits in the queues.
            runners.submit(Activation { release.await() })
            repeat(1_000) { n -> runners.submit(Activation { ran.put("ordinary $n") }) }
            runners.submit(Activation { ran.put("urgent") }, first = true)

            release.countDown()
            runners.awaitIdle()

            ran.first() shouldBe "urgent"
            ran.size shouldBe 1_001
        }

        // Spec 0143: a runner that runs out of work waits to be woken rather than leaving for a new one to start.
        test("activations submitted one after another start no more runners than the parallelism") {
            val runners = Runners(parallelism = 4, cap = 4)
            repeat(10_000) {
                val ran = CountDownLatch(1)
                runners.submit(Activation { ran.countDown() })
                ran.await()
            }
            runners.awaitIdle()

            withClue("runner threads started for 10,000 activations, each queued after the last had run") {
                runners.starts.get() shouldBeLessThanOrEqual 4
            }
        }

        // Spec 0144: a runner whose spins keep ending empty spins less, until a near miss gives it a full one again.
        test("activations submitted 50 µs apart are spun through for less than a quarter of 10 µs each") {
            val runners = Runners(parallelism = 1, cap = 1)
            val before = runners.spun.get()
            repeat(SPACED) {
                val ran = CountDownLatch(1)
                runners.submit(Activation { ran.countDown() })
                ran.await()
                val next = System.nanoTime() + GAP_NANOS
                while (System.nanoTime() < next) Thread.onSpinWait()
            }
            runners.awaitIdle()

            withClue("nanoseconds spun per activation, each submitted 50 µs after the last had run") {
                (runners.spun.get() - before) / SPACED shouldBeLessThan QUARTER_SPIN_NANOS
            }
        }
    }
}
