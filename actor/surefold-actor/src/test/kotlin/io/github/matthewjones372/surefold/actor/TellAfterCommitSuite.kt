package io.github.matthewjones372.surefold.actor

import dev.surefold.Shared
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import dev.surefold.transact
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

private const val THREADS = 1_000

/** Takes every message told it from any thread, which a TestActor, stepped by the test's own thread, does not. */
private class Recorded<M : Any> : ActorRef<M> {
    val told = ConcurrentLinkedQueue<M>()
    override val address = Address("local", "/recorded", 0)

    override fun tell(message: M) {
        told += message
    }
}

/** Spec 0211: an actor is told once per committed step, never once per run. */
object TellAfterCommitSuite : Suite() {
    init {

        test("an actor is told once the step commits") {
            val free = Shared(10)
            val sold = behaviour<Int, Int>(0) { _, total, n -> become(total + n) }.test()

            transact {
                free.update { it - 2 }
                tellAfterCommit(sold, 2)
            }

            sold.state shouldBe 2
            free.peek() shouldBe 8
        }

        test("a step that throws tells nothing") {
            val sold = behaviour<Int, Int>(0) { _, total, n -> become(total + n) }.test()

            shouldThrow<IllegalStateException>({
                transact {
                    tellAfterCommit(sold, 2)
                    error("payment refused")
                }
            }) { }

            sold.state shouldBe 0
        }

        test("a thousand contending steps tell once each, however often they ran") {
            val free = Shared(0)
            val runs = AtomicInteger()
            val orders = Recorded<Int>()

            val buyers = List(THREADS) { i ->
                Thread.ofVirtual().start {
                    transact {
                        runs.incrementAndGet()
                        free.update { it + 1 }
                        tellAfterCommit(orders, i)
                    }
                }
            }
            buyers.forEach { it.join() }

            orders.told.sorted() shouldBe (0 until THREADS).toList()
            free.peek() shouldBe THREADS
            (runs.get() >= THREADS) shouldBe true
        }
    }
}
