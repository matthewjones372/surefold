package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeLeft
import dev.surefold.assert.shouldBeSameInstanceAs
import dev.surefold.assert.shouldThrow
import dev.surefold.fail.attempt
import org.junit.jupiter.api.Test

private data class Closed(val account: String)

class SagaSettleTest {

    @Test
    fun `a credit that throws but happened is undone before the debit it followed`() {
        val timeout = IllegalStateException("timed out")
        val undone = Shared(emptyList<String>())
        val thrown =
            shouldThrow<IllegalStateException> {
                attempt<Closed, Unit> {
                    saga("t-1") {
                        step("debit", { 400 }) { _, _, _ -> undone.update { it + "refund" } }
                        step<Int>(
                            "credit",
                            action = { throw timeout },
                            check = { Settled.Happened(400) },
                        ) { _, credited, _ ->
                            undone.update { it + "reverse $credited" }
                        }
                    }
                }
            }
        thrown shouldBeSameInstanceAs timeout
        undone.peek() shouldBe listOf("reverse 400", "refund")
    }

    @Test
    fun `a step that throws and whose check answers DidNot is not undone, and the ones before it are`() {
        val undone = Shared(emptyList<String>())
        shouldThrow<IllegalStateException> {
            saga<Unit>("t-2") {
                step("debit", { 400 }) { _, _, _ -> undone.update { it + "refund" } }
                step<Int>("credit", action = { error("lost") }, check = { Settled.DidNot }) { _, _, _ ->
                    undone.update { it + "reverse" }
                }
            }
        }
        undone.peek() shouldBe listOf("refund")
    }

    @Test
    fun `a step that throws with no check leaves the saga unsettled, and nothing is undone`() {
        val timeout = IllegalStateException("timed out")
        val undone = Shared(emptyList<String>())
        val thrown =
            shouldThrow<NotUndone> {
                saga<Unit>("t-3") {
                    step("debit", { 400 }) { _, _, _ -> undone.update { it + "refund" } }
                    step<Int>("credit", { throw timeout }) { _, _, _ -> undone.update { it + "reverse" } }
                }
            }
        undone.peek() shouldBe emptyList()
        thrown.unsettled shouldBe listOf("credit")
        thrown.notUndone shouldBe listOf("credit", "debit")
        thrown.cause shouldBeSameInstanceAs timeout
    }

    @Test
    fun `a check that throws leaves the saga unsettled, with the check's throw suppressed`() {
        val down = IllegalStateException("status endpoint down")
        val undone = Shared(emptyList<String>())
        val thrown =
            shouldThrow<NotUndone> {
                saga<Unit>("t-4") {
                    step("debit", { 400 }) { _, _, _ -> undone.update { it + "refund" } }
                    step<Int>("credit", action = { error("lost") }, check = { throw down }) { _, _, _ -> }
                }
            }
        undone.peek() shouldBe emptyList()
        thrown.unsettled shouldBe listOf("credit")
        thrown.suppressed.toList() shouldBe listOf(down)
    }

    @Test
    fun `a declared fail means the step did not happen, so its check is never asked`() {
        val asked = Shared(0)
        val undone = Shared(emptyList<String>())
        val answer =
            attempt<Closed, Unit> {
                saga("t-5") {
                    step("debit", { 400 }) { _, _, _ -> undone.update { it + "refund" } }
                    step<Int>(
                        "credit",
                        action = { fail(Closed("b")) },
                        check = { _ ->
                            asked.update { n -> n + 1 }
                            Settled.Happened(400)
                        },
                    ) { _, _, _ ->
                        undone.update { it + "reverse" }
                    }
                }
            }
        answer shouldBeLeft Closed("b")
        asked.peek() shouldBe 0
        undone.peek() shouldBe listOf("refund")
    }

    @Test
    fun `a check is asked with the step's own key`() {
        val asked = Shared<String?>(null)
        shouldThrow<IllegalStateException> {
            saga<Unit>("t-6") {
                step<Int>(
                    "credit",
                    action = { error("lost") },
                    check = { key ->
                        asked.update { key }
                        Settled.DidNot
                    },
                ) { _, _, _ ->
                }
            }
        }
        asked.peek() shouldBe "t-6/credit"
    }

    @Test
    fun `an interrupted step is unknown too, and its check decides`() {
        val undone = Shared(emptyList<String>())
        shouldThrow<InterruptedException> {
            saga<Unit>("t-7") {
                step("debit", { 400 }) { _, _, _ -> undone.update { it + "refund" } }
                step<Int>(
                    "credit",
                    action = { throw InterruptedException("stop") },
                    check = { Settled.Happened(400) },
                ) { _, _, why ->
                    undone.update { it + "reverse after ${why::class.simpleName}" }
                }
            }
        }
        undone.peek() shouldBe listOf("reverse after Cancelled", "refund")
    }
}
