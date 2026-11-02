package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.fail.Either
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

private const val WAITERS = 20

/** A value handed over once, waited on by many, on the bound clock (spec 0247). */
class PromiseTest {

    @Test
    fun `every waiter gets the one value`() {
        val started = Promise<String, Int>()
        val seen = parZip({ parMap(1..WAITERS) { started.await() } }, { started.succeed(7) }) { all, _ -> all }
        seen.toSet() shouldBe setOf(Either.Right(7))
    }

    @Test
    fun `a second completion answers false and changes nothing`() {
        val started = Promise<String, Int>()
        started.fail("port taken") shouldBe true
        started.succeed(7) shouldBe false
        started.fail("again") shouldBe false
        started.await() shouldBe Either.Left("port taken")
    }

    @Test
    fun `a latch is a promise of Unit, and its value needs no unwrapping`() {
        val stopped = Promise<Nothing, Unit>()
        stopped.peek() shouldBe null
        stopped.succeed(Unit)
        stopped.awaitValue() shouldBe Unit
    }

    @Test
    fun `await within answers null once the bound clock passes it, and not before`() {
        val moving = TestClock()
        val never = Promise<Nothing, Int>()
        val answered = AtomicReference<Any?>("waiting")
        val waiter = Thread.ofVirtual().start {
            clock.locally(moving) { answered.set(never.await(within = 10.seconds)) }
        }
        while (moving.sleepers() == 0) Thread.onSpinWait()
        moving.adjust(9.seconds)
        answered.get() shouldBe "waiting"
        moving.adjust(2.seconds)
        waiter.join()
        answered.get() shouldBe null
    }

    @Test
    fun `a waiter whose thread is interrupted stops waiting`() {
        val never = Promise<Nothing, Int>()
        val ended = AtomicReference<Throwable?>()
        val waiter = Thread.ofVirtual().start {
            try {
                never.await()
            } catch (interrupted: InterruptedException) {
                ended.set(interrupted)
            }
        }
        while (waiter.state != Thread.State.WAITING) Thread.onSpinWait()
        waiter.interrupt()
        waiter.join()
        (ended.get() is InterruptedException) shouldBe true
    }
}
