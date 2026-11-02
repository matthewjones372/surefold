package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeLeft
import dev.surefold.assert.shouldBeRight
import dev.surefold.assert.shouldThrow
import dev.surefold.fail.Either
import dev.surefold.fail.traverse
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class TraverseTest {

    @Test
    fun `a failure at the second of four elements is the Left, and the third and fourth never run`() {
        val ran = mutableListOf<Int>()

        listOf(1, 2, 3, 4).traverse<Bad, Int, Int> { n ->
            ran += n
            if (n == 2) fail(Bad("at $n")) else n
        } shouldBeLeft Bad("at 2")

        ran shouldBe listOf(1, 2)
    }

    @Test
    fun `every element runs on the calling thread, in order, and the values come back in order`() {
        val caller = Thread.currentThread()
        val ran = mutableListOf<Int>()

        listOf(1, 2, 3).traverse<Bad, Int, Int> { n ->
            ran += n
            Thread.currentThread() shouldBe caller
            n * 10
        } shouldBeRight listOf(10, 20, 30)

        ran shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `an empty list answers an empty list`() {
        emptyList<Int>().traverse<Bad, Int, Int> { it } shouldBeRight emptyList()
    }

    @Test
    fun `a throw is not a failure, and leaves as the same instance`() {
        val boom = Boom()

        val thrown = shouldThrow<Boom> { listOf(1, 2).traverse<Bad, Int, Int> { throw boom } }

        (thrown === boom) shouldBe true
    }

    @Test
    fun `parTraverse answers in order, and a failing element is the Left`() {
        listOf(1, 2, 3).parTraverse<Bad, Int, Int> { it * 2 } shouldBeRight listOf(2, 4, 6)
        listOf(1, 2, 3).parTraverse<Bad, Int, Int> { n ->
            if (n == 2) fail(Bad("at $n")) else n
        } shouldBeLeft Bad("at 2")
    }

    @Test
    fun `parTraverse's failing element interrupts a sibling blocked in a sleep`() {
        val sibling = Sleeper()

        listOf(0, 1).parTraverse<Bad, Int, String> { n ->
            if (n == 0) {
                sibling.body()
            } else {
                sibling.awaitStart()
                fail(Bad("refused"))
            }
        } shouldBeLeft Bad("refused")

        sibling.wasInterrupted() shouldBe true
    }

    @Test
    fun `with no limit, parTraverse runs PAR_TRAVERSE_DEFAULT elements at once and never more`() {
        // The first PAR_TRAVERSE_DEFAULT can leave only once that many are inside together.
        val together = CountDownLatch(PAR_TRAVERSE_DEFAULT)
        val inside = AtomicInteger()
        val most = AtomicInteger()

        val met = (1..200).toList().parTraverse<Bad, Int, Boolean> {
            most.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
            together.rendezvous().also { inside.decrementAndGet() }
        }

        met shouldBe Either.Right(List(200) { true })
        most.get() shouldBe PAR_TRAVERSE_DEFAULT
    }

    @Test
    fun `a limit given to parTraverse overrides the default`() {
        val together = CountDownLatch(2)
        val inside = AtomicInteger()
        val most = AtomicInteger()

        val met = (1..20).toList().parTraverse<Bad, Int, Boolean>(Limit.fixed(2)) {
            most.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
            together.rendezvous().also { inside.decrementAndGet() }
        }

        met shouldBe Either.Right(List(20) { true })
        most.get() shouldBe 2
    }
}
