package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldThrow
import dev.surefold.assert.withClue
import java.util.concurrent.CountDownLatch
import org.junit.jupiter.api.Test

private const val ROWS = 2_000

/** Spec 0038: waiting on channels and forks at once. */
class SelectTest {

    @Test
    fun `a select on an empty channel and a fork answers with the fork, and leaves no waiter on the channel`() {
        scope {
            val rows = channel<Int>(1)
            val fork = async { "done" }
            val answer = select {
                rows.onReceive { "row $it" }
                fork.onAwait { it }
            }

            answer shouldBe "done"
            withClue("threads parked on the channel once the select has answered") { rows.parked() shouldBe 0 }
        }
    }

    @Test
    fun `a value that arrives first wins over a fork still running, and the fork is no longer waited for`() {
        scope {
            val rows = channel<Int>(0)
            val hold = CountDownLatch(1)
            val fork = async { hold.await() }
            async { rows.send(7) }
            val answer = select {
                fork.onAwait { -1 }
                rows.onReceive { it }
            }

            answer shouldBe 7
            withClue("threads waiting for the fork once the select has answered") {
                (fork as Fork<*, *>).waiting() shouldBe 0
            }
            hold.countDown()
            fork.await()
        }
    }

    @Test
    fun `of clauses ready together the first written wins`() {
        scope {
            val first = channel<String>(1).apply { send("first") }
            val second = channel<String>(1).apply { send("second") }
            select {
                first.onReceive { it }
                second.onReceive { it }
            } shouldBe "first"
            second.receive() shouldBe "second"
        }
    }

    @Test
    fun `a select that sends hands its value to the one channel read, and nothing to the other`() {
        scope {
            val unread = channel<Int>(0)
            val read = channel<Int>(0)
            val received = async { read.receive() }
            select {
                unread.onSend(1) { "unread" }
                read.onSend(2) { "read" }
            } shouldBe "read"
            received.await() shouldBe 2
            unread.parked() shouldBe 0
        }
    }

    @Test
    fun `a select sending meets a select receiving on a channel with no room`() {
        scope {
            val rows = channel<Int>(0)
            val idle = channel<Int>(0)
            val alsoIdle = channel<Int>(0)
            async {
                select {
                    rows.onSend(5) {}
                    idle.onReceive {}
                }
            }
            select {
                alsoIdle.onSend(0) { -1 }
                rows.onReceive { it }
            } shouldBe 5
        }
    }

    @Test
    fun `a select interrupted before anything wins throws, and leaves no waiter behind`() {
        scope {
            val rows = channel<Int>(0)
            val parked = CountDownLatch(1)
            val selecting = async {
                parked.countDown()
                select { rows.onReceive { it } }
            }
            parked.await()
            while (rows.parked() == 0) Thread.onSpinWait()
            selecting.cancel()
            shouldThrow<InterruptedException> { selecting.await() }
            rows.parked() shouldBe 0
        }
    }

    @Test
    fun `two consumers selecting over two channels between them receive every value sent exactly once`() {
        val total = scope {
            val evens = channel<Int>(2)
            val odds = channel<Int>(0)
            async {
                (0 until ROWS step 2).forEach { evens.send(it) }
                evens.close()
            }
            async {
                (1 until ROWS step 2).forEach { odds.send(it) }
                odds.close()
            }
            val consumers =
                List(2) {
                    async {
                        val open = mutableListOf(evens, odds)
                        var sum = 0L
                        while (open.isNotEmpty()) {
                            val (from, value) = select { open.forEach { rows -> rows.onReceive { rows to it } } }
                            if (value == null) open -= from else sum += value
                        }
                        sum
                    }
                }
            consumers.sumOf { it.await() }
        }

        total shouldBe (0 until ROWS).sumOf { it.toLong() }
    }

    @Test
    fun `only a fork async opened can be selected on`() {
        val elsewhere =
            object : Deferred<Int> {
                override fun await() = 1

                override fun cancel() = Unit
            }
        shouldThrow<IllegalArgumentException> { select { elsewhere.onAwait { it } } }
    }
}
