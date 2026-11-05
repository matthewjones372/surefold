package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeLeft
import dev.surefold.assert.shouldBeRight
import dev.surefold.assert.shouldThrow
import dev.surefold.fail.NonEmptyList
import dev.surefold.fail.attempt
import dev.surefold.fail.nonEmptyListOf
import org.junit.jupiter.api.Test

class MapOrAccumulateTest {

    @Test
    fun `failures at the first and third elements answer with both, in input order`() {
        attempt<NonEmptyList<Bad>, List<Int>> {
            mapOrAccumulate(listOf(1, 2, 3, 4)) { n -> if (n % 2 == 1) fail(Bad("odd $n")) else n }
        } shouldBeLeft nonEmptyListOf(Bad("odd 1"), Bad("odd 3"))
    }

    @Test
    fun `every element succeeding answers with the values in input order`() {
        attempt<NonEmptyList<Bad>, List<Int>> {
            mapOrAccumulate(listOf(1, 2, 3)) { it * 10 }
        } shouldBeRight listOf(10, 20, 30)
    }

    @Test
    fun `an empty list answers an empty list`() {
        attempt<NonEmptyList<Bad>, List<Int>> {
            mapOrAccumulate(emptyList<Int>()) { it }
        } shouldBeRight emptyList()
    }

    @Test
    fun `every element runs on the calling thread, in order`() {
        val caller = Thread.currentThread()
        val ran = mutableListOf<Int>()

        attempt<NonEmptyList<Bad>, List<Int>> {
            mapOrAccumulate(listOf(1, 2, 3)) { n ->
                ran += n
                Thread.currentThread() shouldBe caller
                n
            }
        }

        ran shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `a throw ends the map where it happens and is rethrown as the same instance`() {
        val boom = Boom()
        val ran = mutableListOf<Int>()

        val thrown =
            shouldThrow<Boom> {
                attempt<NonEmptyList<Bad>, List<Int>> {
                    mapOrAccumulate(listOf(1, 2, 3)) { n ->
                        ran += n
                        if (n == 2) throw boom else n
                    }
                }
            }

        (thrown === boom) shouldBe true
        ran shouldBe listOf(1, 2)
    }

    @Test
    fun `combine folds the failures into the single error the scope declares`() {
        attempt<Bad, List<Int>> {
            mapOrAccumulate({ left, right -> Bad(left.why + right.why) }, listOf(1, 2, 3)) { n ->
                if (n == 2) n else fail(Bad("$n"))
            }
        } shouldBeLeft Bad("13")
        attempt<Bad, List<Int>> {
            mapOrAccumulate({ left, _ -> left }, listOf(1, 2)) { it + 1 }
        } shouldBeRight listOf(2, 3)
    }
}
