package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeLeft
import dev.surefold.assert.shouldContainExactlyInAnyOrder
import dev.surefold.fail.attempt
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test

private data class NoRooms(val hotel: String)

class SagaForksTest {

    @Test
    fun `a step taken in one parZip branch is undone when the other branch fails`() {
        val held = CountDownLatch(1)
        val undone = Shared(emptyList<String>())
        val answer =
            attempt<NoRooms, Pair<Int, Int>> {
                saga("t-1") {
                    parZip(
                        { step("car", { 1 }) { _, _, _ -> undone.update { it + "car" } }.also { held.countDown() } },
                        {
                            held.await(5, TimeUnit.SECONDS)
                            step<Int>("room", { fail(NoRooms("plaza")) }) { _, _, _ -> undone.update { it + "room" } }
                        },
                    ) { car, room ->
                        car to room
                    }
                }
            }
        answer shouldBeLeft NoRooms("plaza")
        undone.peek() shouldBe listOf("car")
    }

    @Test
    fun `undos of steps from forks run in the reverse of the order their actions returned`() {
        val order = Shared(emptyList<String>())
        val undone = Shared(emptyList<String>())
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        attempt<NoRooms, Unit> {
            saga("t-2") {
                parZip(
                    {
                        second.await(5, TimeUnit.SECONDS)
                        step("late", { _ -> order.update { it + "late" } }) { _, _, _ -> undone.update { it + "late" } }
                    },
                    {
                        step("early", { _ -> order.update { it + "early" } }) { _, _, _ ->
                            undone.update { it + "early" }
                        }
                        first.countDown()
                        second.countDown()
                    },
                ) { _, _ ->
                }
                first.await(5, TimeUnit.SECONDS)
                fail(NoRooms("after"))
            }
        }
        order.peek() shouldBe listOf("early", "late")
        undone.peek() shouldBe listOf("late", "early")
    }

    @Test
    fun `steps taken in parMap are each undone once`() {
        val undone = Shared(emptyList<Int>())
        attempt<NoRooms, Unit> {
            saga("t-3") {
                parMap((1..20).toList()) { n -> step("seat-$n", { n }) { _, seat, _ -> undone.update { it + seat } } }
                fail(NoRooms("after"))
            }
        }
        undone.peek() shouldContainExactlyInAnyOrder (1..20).toList()
    }

    @Test
    fun `a run records its outcome, tagged by the saga's name rather than its id`() {
        capturingMetrics { measured ->
            attempt<NoRooms, Unit> { saga("t-4", name = "booking") { fail(NoRooms("a")) } }
            measured.counter("surefold.saga.runs") shouldBe 1.0
            measured.tags("surefold.saga.runs") shouldBe mapOf("saga" to "booking", "outcome" to "undone")
        }
        capturingMetrics { measured ->
            saga("t-5") { 1 }
            measured.tags("surefold.saga.runs") shouldBe mapOf("saga" to "unnamed", "outcome" to "done")
        }
        capturingMetrics { measured ->
            runCatchingNotUndone {
                saga<Unit>("t-6") {
                    step("x", { 1 }) { _, _, _ -> error("down") }
                    error("after")
                }
            }
            measured.tags("surefold.saga.runs") shouldBe mapOf("saga" to "unnamed", "outcome" to "not-undone")
        }
    }

    @Test
    fun `a check records what it answered`() {
        capturingMetrics { measured ->
            runCatchingIllegalState {
                saga<Unit>("t-7", name = "transfer") {
                    step<Int>("credit", action = { error("lost") }, check = { Settled.DidNot }) { _, _, _ -> }
                }
            }
            measured.tags("surefold.saga.checks") shouldBe mapOf("saga" to "transfer", "answer" to "did-not")
        }
    }

    private fun runCatchingNotUndone(block: () -> Unit) {
        try {
            block()
        } catch (expected: NotUndone) {
            expected.saga shouldBe "t-6"
        }
    }

    private fun runCatchingIllegalState(block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalStateException) {
            expected.message shouldBe "lost"
        }
    }
}
