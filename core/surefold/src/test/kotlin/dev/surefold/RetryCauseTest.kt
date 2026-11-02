package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContainExactly
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue

class RetryCauseTest {

    private fun retriesOf(block: () -> Unit): List<Pair<String, String>> {
        val seen = ConcurrentLinkedQueue<Pair<String, String>>()
        retried.locally({ step, cause -> seen += step to cause }) { block() }
        return seen.toList()
    }

    // Commits a change to [shared] from another thread, so the run that read it cannot commit.
    private fun <T> changeElsewhere(shared: Shared<T>, change: (T) -> T) =
        Thread.ofVirtual().start { transact { shared.update(change) } }.join()

    @Test
    fun `a step that loses its commit to a named value names it as the cause`() {
        val stalls = Shared(10, name = "free.stalls")
        var runs = 0

        val retries = retriesOf {
            transact("hold") {
                val left = stalls.get()
                if (++runs == 1) changeElsewhere(stalls) { it + 1 }
                stalls.set(left - 1)
            }
        }

        retries shouldContainExactly listOf("hold" to "free.stalls")
        stalls.peek() shouldBe 10
    }

    @Test
    fun `a step that sees a later value while reading names the value it had read first`() {
        val stalls = Shared(10, name = "free.stalls")
        val circle = Shared(5, name = "free.circle")
        var runs = 0

        val retries = retriesOf {
            transact("hold") {
                stalls.get()
                if (++runs == 1) {
                    changeElsewhere(stalls) { it - 1 }
                    changeElsewhere(circle) { it - 1 }
                }
                circle.get()
            }
        }

        retries shouldContainExactly listOf("hold" to "free.stalls")
    }

    @Test
    fun `a step and a value with no name are reported as unnamed`() {
        val free = Shared(10)
        var runs = 0

        val retries = retriesOf {
            transact {
                val left = free.get()
                if (++runs == 1) changeElsewhere(free) { it + 1 }
                free.set(left - 1)
            }
        }

        retries shouldContainExactly listOf("unnamed" to "unnamed")
    }

    @Test
    fun `a step that waited reports waited`() {
        val free = Shared(0, name = "free")

        val retries = retriesOf {
            val buyer = Thread.ofVirtual().start {
                retried.locally({ step, cause -> retriedHere += step to cause }) {
                    transact("hold") { if (free.get() == 0) waitForChange() else free.update { it - 1 } }
                }
            }
            while (free.waiters.isEmpty()) Thread.onSpinWait()
            transact { free.set(1) }
            buyer.join()
        }

        retries shouldBe emptyList()
        retriedHere.toList() shouldContainExactly listOf("hold" to "waited")
    }

    @Test
    fun `a step that commits on its first run reports nothing`() {
        val free = Shared(10, name = "free")

        retriesOf { transact("hold") { free.update { it - 1 } } } shouldBe emptyList()
    }

    private val retriedHere = ConcurrentLinkedQueue<Pair<String, String>>()
}
