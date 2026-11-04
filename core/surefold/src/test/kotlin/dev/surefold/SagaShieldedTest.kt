package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeInstanceOf
import dev.surefold.assert.shouldThrow
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SagaShieldedTest {

    @Test
    fun `a saga interrupted mid-block runs an undo that blocks, to the end, however often it is interrupted`() {
        val started = CountDownLatch(1)
        val undoing = CountDownLatch(1)
        val finished = Shared(false)
        val ended = Shared<Throwable?>(null)
        val reinterrupted = Shared(false)
        val saga = Thread.ofVirtual().start {
            try {
                saga<Unit>("t-1") {
                    step("hold", { 1 }) { _, _, _ ->
                        undoing.countDown()
                        Thread.sleep(50)
                        finished.update { true }
                    }
                    started.countDown()
                    Thread.sleep(10_000)
                }
            } catch (interrupt: InterruptedException) {
                ended.update { interrupt }
                reinterrupted.update { Thread.currentThread().isInterrupted }
            }
        }
        started.await(5, TimeUnit.SECONDS)
        saga.interrupt()
        undoing.await(5, TimeUnit.SECONDS)
        saga.interrupt()
        saga.join(5_000)
        finished.peek() shouldBe true
        ended.peek().shouldBeInstanceOf<InterruptedException>()
        reinterrupted.peek() shouldBe true
    }

    @Test
    fun `an undo runs on a virtual thread of its own, with the saga's locals`() {
        val local = surefoldLocal { "unset" }
        val seen = Shared<Pair<Thread, String>?>(null)
        val caller = Thread.currentThread()
        shouldThrow<IllegalStateException> {
            local.locally("request-7") {
                saga<Unit>("t-2") {
                    step("hold", { 1 }) { _, _, _ -> seen.update { Thread.currentThread() to local.get() } }
                    error("after")
                }
            }
        }
        val (thread, value) = seen.peek()!!
        (thread === caller) shouldBe false
        thread.isVirtual shouldBe true
        value shouldBe "request-7"
    }

    @Test
    fun `an undo that fails twice is retried by the saga's undo policy`() {
        val tries = Shared(0)
        val undoing = policy("undo") { retry(Schedule.recurs(3)) }
        shouldThrow<IllegalStateException> {
            saga<Unit>("t-3", undo = undoing) {
                step("hold", { 1 }) { _, _, _ ->
                    if (tries.modify { (it + 1) to (it + 1) } < 3) error("refund down")
                }
                error("after")
            }
        }
        tries.peek() shouldBe 3
    }

    @Test
    fun `a check is asked through the saga's undo policy too`() {
        val asked = Shared(0)
        val undoing = policy("undo") { retry(Schedule.recurs(3)) }
        val undone = Shared(false)
        shouldThrow<IllegalStateException> {
            saga<Unit>("t-4", undo = undoing) {
                step<Int>(
                    "credit",
                    action = { error("lost") },
                    check = { _ ->
                        if (asked.modify { (it + 1) to (it + 1) } < 2) error("status down")
                        Settled.Happened(1)
                    },
                ) { _, _, _ -> undone.update { true } }
            }
        }
        asked.peek() shouldBe 2
        undone.peek() shouldBe true
    }

    @Test
    fun `the undo policy's deadline bounds every undo of the saga together`() {
        val undoing = policy("undo") { deadline(600.milliseconds) }
        val thrown = shouldThrow<NotUndone> {
            saga<Unit>("t-5", undo = undoing) {
                step("first", { 1 }) { _, _, _ -> Thread.sleep(400) }
                step("second", { 2 }) { _, _, _ -> Thread.sleep(400) }
                error("after")
            }
        }
        thrown.notUndone shouldBe listOf("first")
        thrown.suppressed.single().shouldBeInstanceOf<TimeoutException>()
    }

    @Test
    fun `an undo that ends inside the deadline is untouched by it`() {
        val undoing = policy("undo") { deadline(5.seconds) }
        val undone = Shared(0)
        shouldThrow<IllegalStateException> {
            saga<Unit>("t-6", undo = undoing) {
                step("first", { 1 }) { _, _, _ -> undone.update { it + 1 } }
                step("second", { 2 }) { _, _, _ -> undone.update { it + 1 } }
                error("after")
            }
        }
        undone.peek() shouldBe 2
    }
}
