package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeSameInstanceAs
import dev.surefold.assert.shouldThrow
import org.junit.jupiter.api.Test

private const val ACCOUNTS = 10
private const val THREADS = 1_000
private const val TRANSFERS = 20

class McasTest {

    private class Box(val n: Int)

    @Test
    fun `a swap that finds every word as expected writes them all`() {
        val a = Word("a")
        val b = Word("b")

        swapAll(listOf(Change(a, "a", "A"), Change(b, "b", "B"))) shouldBe true

        a.read() shouldBe "A"
        b.read() shouldBe "B"
    }

    @Test
    fun `a swap that finds one word changed writes none`() {
        val a = Word("a")
        val b = Word("b")

        swapAll(listOf(Change(a, "a", "A"), Change(b, "not b", "B"))) shouldBe false

        a.read() shouldBe "a"
        b.read() shouldBe "b"
    }

    @Test
    fun `an equal value that is not the same instance does not match`() {
        val held = Box(1)
        val word = Word(held)

        swapAll(listOf(Change(word, Box(1), Box(2)))) shouldBe false

        word.read() shouldBeSameInstanceAs held
    }

    @Test
    fun `a swap that names a word twice is refused`() {
        val word = Word("a")

        shouldThrow<IllegalArgumentException> { swapAll(listOf(Change(word, "a", "b"), Change(word, "a", "c"))) }
    }

    @Test
    fun `transfers on a thousand virtual threads leave the total unchanged`() {
        val accounts = List(ACCOUNTS) { Word(Box(100)) }

        val threads =
            List(THREADS) { t ->
                Thread.ofVirtual().start {
                    repeat(TRANSFERS) { i ->
                        val from = accounts[(t + i) % ACCOUNTS]
                        val to = accounts[(t + i + 1 + t % (ACCOUNTS - 1)) % ACCOUNTS]
                        do {
                            val f = from.read()
                            val o = to.read()
                        } while (!swapAll(listOf(Change(from, f, Box(f.n - 1)), Change(to, o, Box(o.n + 1)))))
                    }
                }
            }
        threads.forEach { it.join() }

        accounts.sumOf { it.read().n } shouldBe ACCOUNTS * 100
    }
}
