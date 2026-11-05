package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldThrow
import dev.surefold.assert.withClue
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test

private const val ROWS = 1_000
private const val CAPACITY = 8
private const val SLOW_EVERY = 50

/** Spec 0038: a bounded hand-off between forks. */
class ChannelTest {

    @Test
    fun `a producer far ahead of a slow consumer never holds more than the capacity, and all of it arrives in order`() {
        val most = AtomicInteger()
        val received = scope {
            val rows = channel<Int>(CAPACITY)
            async {
                repeat(ROWS) { row -> rows.send(row) }
                rows.close()
            }
            rows.map { row ->
                most.accumulateAndGet(rows.buffered(), ::maxOf)
                if (row % SLOW_EVERY == 0) Thread.sleep(1)
                row
            }
        }

        received shouldBe (0 until ROWS).toList()
        withClue("the most values waiting in the channel at once") { (most.get() <= CAPACITY) shouldBe true }
    }

    @Test
    fun `a consumer that cancels ends its producer, which sees false and stops without an interrupt`() {
        val result = scope {
            val rows = channel<Int>(CAPACITY)
            val sent = async {
                var count = 0
                while (rows.send(count)) count++
                // Stopped by the cancel, not by an interrupt from the scope.
                check(!Thread.currentThread().isInterrupted)
                count
            }
            val firstTen = rows.take(10)
            rows.cancel()
            // The producer had every value the consumer took in hand before the cancel, and more besides.
            firstTen to (sent.await() >= firstTen.size)
        }

        result shouldBe ((0 until 10).toList() to true)
    }

    @Test
    fun `a capacity of zero hands each value straight from the sender to the receiver`() {
        val result = scope {
            val handOff = channel<String>(0)
            async {
                listOf("a", "b", "c").forEach { handOff.send(it) }
                handOff.close()
            }
            handOff.toList()
        }

        result shouldBe listOf("a", "b", "c")
    }

    @Test
    fun `a send on a closed channel throws, and a receive answers null once it is drained`() {
        scope {
            val rows = channel<Int>(2)
            rows.send(1)
            rows.close()
            shouldThrow<IllegalStateException> { rows.send(2) }
            rows.receive() shouldBe 1
            rows.receive() shouldBe null
        }
    }

    @Test
    fun `a producer parked on a full channel nobody reads is released when the scope closes`() {
        val released = AtomicInteger(-1)
        scope {
            val rows = channel<Int>(1)
            async {
                rows.send(1)
                released.set(if (rows.send(2)) 1 else 0)
            }
        }

        withClue("the second send, parked on a full channel when the scope closed, answered false") {
            released.get() shouldBe 0
        }
    }

    @Test
    fun `a channel cannot hold fewer than zero values`() {
        shouldThrow<IllegalArgumentException> { Channel<Int>(-1) }
    }
}
