package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeInstanceOf
import dev.surefold.assert.shouldBeLeft
import dev.surefold.assert.shouldBeRight
import dev.surefold.assert.shouldBeSameInstanceAs
import dev.surefold.assert.shouldThrow
import dev.surefold.fail.attempt
import org.junit.jupiter.api.Test

private data class Refused(val why: String)

class SagaTest {

    @Test
    fun `a fail in the third step runs the first two undos newest first, each told why`() {
        val undone = Shared(emptyList<Pair<String, Undo<Refused>>>())
        val answer =
            attempt<Refused, Int> {
                saga("t-1") {
                    step("debit", { 1 }) { _, _, why -> undone.update { it + ("debit" to why) } }
                    step("hold", { 2 }) { _, _, why -> undone.update { it + ("hold" to why) } }
                    step<Int>("credit", { fail(Refused("closed")) }) { _, _, _ ->
                        undone.update {
                            it + ("credit" to Undo.Failed(Refused("never")))
                        }
                    }
                }
            }
        answer shouldBeLeft Refused("closed")
        undone.peek() shouldBe
            listOf(
                "hold" to Undo.Failed(Refused("closed")),
                "debit" to Undo.Failed(Refused("closed")),
            )
    }

    @Test
    fun `a saga that returns runs no undo and answers its value`() {
        val undone = Shared(0)
        val answer =
            attempt<Refused, Int> {
                saga("t-2") {
                    val a = step("one", { 1 }) { _, _, _ -> undone.update { it + 1 } }
                    val b = step("two", { 2 }) { _, _, _ -> undone.update { it + 1 } }
                    a + b
                }
            }
        answer shouldBeRight 3
        undone.peek() shouldBe 0
    }

    @Test
    fun `each step's action and undo are handed keys from the saga's id and the step's name`() {
        val keys = Shared(emptyList<String>())
        attempt<Refused, Unit> {
            saga("t-3") {
                step("debit", { key -> keys.update { it + key } }) { key, _, _ -> keys.update { it + key } }
                fail(Refused("after"))
            }
        }
        keys.peek() shouldBe listOf("t-3/debit", "t-3/debit/undo")
    }

    @Test
    fun `an undo is handed what its action returned`() {
        val handed = Shared<String?>(null)
        attempt<Refused, Unit> {
            saga("t-4") {
                step("hold", { "seat-12" }) { _, held, _ -> handed.update { held } }
                fail(Refused("no"))
            }
        }
        handed.peek() shouldBe "seat-12"
    }

    @Test
    fun `a throw is told Threw, runs the undos, and carries on as the same throw`() {
        val boom = IllegalStateException("boom")
        val told = Shared<Undo<Refused>?>(null)
        val thrown =
            shouldThrow<IllegalStateException> {
                attempt<Refused, Unit> {
                    saga("t-5") {
                        step("debit", { 1 }) { _, _, why -> told.update { why } }
                        throw boom
                    }
                }
            }
        thrown shouldBeSameInstanceAs boom
        told.peek() shouldBe Undo.Threw(boom)
    }

    @Test
    fun `an interrupt is told Cancelled`() {
        val told = Shared<Undo<Refused>?>(null)
        shouldThrow<InterruptedException> {
            attempt<Refused, Unit> {
                saga("t-6") {
                    step("debit", { 1 }) { _, _, why -> told.update { why } }
                    throw InterruptedException("stop")
                }
            }
        }
        told.peek().shouldBeInstanceOf<Undo.Cancelled>()
    }

    @Test
    fun `a step whose action does not return records no undo`() {
        val undone = Shared(emptyList<String>())
        attempt<Refused, Unit> {
            saga("t-7") {
                step("debit", { 1 }) { _, _, _ -> undone.update { it + "debit" } }
                step<Unit>("credit", { fail(Refused("closed")) }) { _, _, _ -> undone.update { it + "credit" } }
            }
        }
        undone.peek() shouldBe listOf("debit")
    }

    @Test
    fun `an undo that throws does not stop the others, and the saga ends in NotUndone`() {
        val refund = IllegalStateException("refund down")
        val undone = Shared(emptyList<String>())
        val thrown =
            shouldThrow<NotUndone> {
                attempt<Refused, Unit> {
                    saga("t-8") {
                        step("debit", { 1 }) { _, _, _ -> undone.update { it + "debit" } }
                        step("hold", { 2 }) { _, _, _ -> throw refund }
                        fail(Refused("closed"))
                    }
                }
            }
        undone.peek() shouldBe listOf("debit")
        thrown.saga shouldBe "t-8"
        thrown.notUndone shouldBe listOf("hold")
        thrown.why shouldBe Undo.Failed(Refused("closed"))
        thrown.suppressed.toList() shouldBe listOf(refund)
    }

    @Test
    fun `NotUndone after a throw has that throw as its cause`() {
        val boom = IllegalStateException("boom")
        val thrown =
            shouldThrow<NotUndone> {
                saga<Unit>("t-12") {
                    step("hold", { 1 }) { _, _, _ -> error("release down") }
                    throw boom
                }
            }
        thrown.cause shouldBeSameInstanceAs boom
    }

    @Test
    fun `a fail raised through a scope inside the saga is still the saga's own`() {
        val told = Shared<Undo<Refused>?>(null)
        val answer =
            attempt<Refused, Unit> {
                saga("t-9") {
                    step("debit", { 1 }) { _, _, why -> told.update { why } }
                    scope { fail(Refused("nested")) }
                }
            }
        answer shouldBeLeft Refused("nested")
        told.peek() shouldBe Undo.Failed(Refused("nested"))
    }

    @Test
    fun `a fail into an outer attempt of another type is told Threw, and still reaches that attempt`() {
        val told = Shared<Undo<Refused>?>(null)
        val outer =
            attempt<String, Unit> outer@{
                val inner =
                    attempt<Refused, Unit> {
                        saga("t-10") {
                            step("debit", { 1 }) { _, _, why -> told.update { why } }
                            this@outer.fail("outer")
                        }
                    }
                inner shouldBeRight Unit
            }
        outer shouldBeLeft "outer"
        told.peek().shouldBeInstanceOf<Undo.Threw>()
    }

    @Test
    fun `two steps with one name in a saga are refused, undoing the first`() {
        val undone = Shared(0)
        shouldThrow<IllegalArgumentException> {
            attempt<Refused, Int> {
                saga("t-11") {
                    step("debit", { 1 }) { _, _, _ -> undone.update { it + 1 } }
                    step("debit", { 2 }) { _, _, _ -> undone.update { it + 10 } }
                }
            }
        }
        undone.peek() shouldBe 1
    }
}
