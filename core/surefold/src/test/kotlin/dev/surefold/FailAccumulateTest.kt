package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeLeft
import dev.surefold.assert.shouldBeRight
import dev.surefold.assert.shouldThrow
import dev.surefold.fail.NonEmptyList
import dev.surefold.fail.attempt
import dev.surefold.fail.nonEmptyListOf
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

// Long enough that an interrupt sent by a sibling's fail would have landed inside the pause below.
private const val SETTLE_MILLIS = 250L

/** A branch that keeps running after a sibling has failed, and says whether anything interrupted it. */
private class Bystander {
    private val interrupted = AtomicBoolean(false)

    fun waitOutAFail(failed: CountDownLatch): String =
        try {
            failed.await()
            Thread.sleep(SETTLE_MILLIS)
            "completed"
        } catch (stop: InterruptedException) {
            interrupted.set(true)
            "interrupted"
        }

    fun wasInterrupted(): Boolean = interrupted.get()
}

class FailAccumulateTest {

    @Test
    fun `two failing branches answer with a NonEmptyList of both, in branch order, not finishing order`() {
        attempt<NonEmptyList<Bad>, Int> {
            parZipOrAccumulate(
                { fail(Bad("first")) },
                { fail(Bad("second")) },
            ) { _: Int, _: Int -> 0 }
        } shouldBeLeft nonEmptyListOf(Bad("first"), Bad("second"))
    }

    @Test
    fun `a failing branch does not interrupt its sibling`() {
        val failed = CountDownLatch(1)
        val bystander = Bystander()

        val accumulated = attempt<NonEmptyList<Bad>, String> {
            parZipOrAccumulate(
                {
                    failed.countDown()
                    fail(Bad("failed"))
                },
                { bystander.waitOutAFail(failed) },
            ) { _: Int, sibling: String -> sibling }
        }

        accumulated shouldBeLeft nonEmptyListOf(Bad("failed"))
        // Every branch runs to completion, so a fail cannot end a sibling.
        bystander.wasInterrupted() shouldBe false
    }

    @Test
    fun `combine folds the failures into the single error the scope declares`() {
        attempt<Bad, Int> {
            parZipOrAccumulate(
                { left, right -> Bad(left.why + right.why) },
                { fail(Bad("a")) },
                { fail(Bad("b")) },
            ) { _: Int, _: Int -> 0 }
        } shouldBeLeft Bad("ab")
    }

    @Test
    fun `a throw among the failures rethrows the same instance and interrupts the rest`() {
        val boom = Boom()
        val sleeper = Sleeper()

        val thrown = shouldThrow<Boom> {
            attempt<NonEmptyList<Bad>, Int> {
                parZipOrAccumulate(
                    { fail(Bad("failed")) },
                    { sleeper.body() },
                    {
                        sleeper.awaitStart()
                        throw boom
                    },
                ) { _: Int, _: String, _: Int -> 0 }
            }
        }

        (thrown === boom) shouldBe true
        // A throw is not accumulated: it ends the others as parZip's does.
        sleeper.wasInterrupted() shouldBe true
        sleeper.isAlive() shouldBe false
    }

    @Test
    fun `parZipOrAccumulate combines two to five branches`() {
        attempt<NonEmptyList<Bad>, String> {
            parZipOrAccumulate({ "a" }, { "b" }) { a, b -> a + b }
        } shouldBeRight "ab"
        attempt<NonEmptyList<Bad>, String> {
            parZipOrAccumulate({ "a" }, { "b" }, { "c" }) { a, b, c -> a + b + c }
        } shouldBeRight "abc"
        attempt<NonEmptyList<Bad>, String> {
            parZipOrAccumulate({ "a" }, { "b" }, { "c" }, { "d" }) { a, b, c, d -> a + b + c + d }
        } shouldBeRight "abcd"
        attempt<NonEmptyList<Bad>, String> {
            parZipOrAccumulate({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }) { a, b, c, d, e ->
                a + b + c + d + e
            }
        } shouldBeRight "abcde"
    }

    @Test
    fun `the combine form combines two to five branches`() {
        val worst: (Bad, Bad) -> Bad = { left, _ -> left }

        attempt<Bad, String> { parZipOrAccumulate(worst, { "a" }, { "b" }) { a, b -> a + b } } shouldBeRight "ab"
        attempt<Bad, String> {
            parZipOrAccumulate(worst, { "a" }, { "b" }, { "c" }) { a, b, c -> a + b + c }
        } shouldBeRight "abc"
        attempt<Bad, String> {
            parZipOrAccumulate(worst, { "a" }, { "b" }, { "c" }, { "d" }) { a, b, c, d -> a + b + c + d }
        } shouldBeRight "abcd"
        attempt<Bad, String> {
            parZipOrAccumulate(worst, { "a" }, { "b" }, { "c" }, { "d" }, { "e" }) { a, b, c, d, e ->
                a + b + c + d + e
            }
        } shouldBeRight "abcde"
    }

    @Test
    fun `parMapOrAccumulate keeps the failure of every element that failed`() {
        attempt<NonEmptyList<Bad>, List<Int>> {
            parMapOrAccumulate(listOf(1, 2, 3, 4)) { element ->
                if (element == 4) element else fail(Bad("element $element"))
            }
        } shouldBeLeft nonEmptyListOf(Bad("element 1"), Bad("element 2"), Bad("element 3"))

        attempt<NonEmptyList<Bad>, List<Int>> {
            parMapOrAccumulate(listOf(1, 2, 3)) { it * 2 }
        } shouldBeRight listOf(2, 4, 6)
    }

    @Test
    fun `parMapOrAccumulate folds its failures with combine`() {
        attempt<Bad, List<Int>> {
            parMapOrAccumulate({ left, right -> Bad(left.why + right.why) }, listOf(1, 2)) { element ->
                fail(Bad("$element"))
            }
        } shouldBeLeft Bad("12")

        attempt<Bad, List<Int>> {
            parMapOrAccumulate({ left, _ -> left }, listOf(1, 2)) { it * 2 }
        } shouldBeRight listOf(2, 4)
    }

    @Test
    fun `parZipOrAccumulate combines six to eight branches`() {
        attempt<NonEmptyList<Bad>, String> {
            parZipOrAccumulate({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }) { a, b, c, d, e, f ->
                a + b + c + d + e + f
            }
        } shouldBeRight "abcdef"

        attempt<NonEmptyList<Bad>, String> {
            parZipOrAccumulate({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" }) { a, b, c, d, e, f, g ->
                a + b + c + d + e + f + g
            }
        } shouldBeRight "abcdefg"

        attempt<NonEmptyList<Bad>, String> {
            parZipOrAccumulate(
                { "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" }, { "h" },
            ) { a, b, c, d, e, f, g, h -> a + b + c + d + e + f + g + h }
        } shouldBeRight "abcdefgh"
    }

    @Test
    fun `a nine-branch parZipOrAccumulate answers, and keeps all nine failures`() {
        attempt<NonEmptyList<Bad>, String> {
            parZipOrAccumulate(
                { "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" }, { "h" }, { "i" },
            ) { a, b, c, d, e, f, g, h, i -> a + b + c + d + e + f + g + h + i }
        } shouldBeRight "abcdefghi"

        val allNine = attempt<NonEmptyList<Bad>, String> {
            parZipOrAccumulate(
                { fail(Bad("1")) },
                { fail(Bad("2")) },
                { fail(Bad("3")) },
                { fail(Bad("4")) },
                { fail(Bad("5")) },
                { fail(Bad("6")) },
                { fail(Bad("7")) },
                { fail(Bad("8")) },
                { fail(Bad("9")) },
            ) { _: String, _: String, _: String, _: String, _: String,
                _: String, _: String, _: String, _: String,
                ->
                ""
            }
        }

        allNine shouldBeLeft nonEmptyListOf(Bad("1"), *(2..9).map { Bad("$it") }.toTypedArray())
    }

    @Test
    fun `the combine form combines six to nine branches`() {
        val worst: (Bad, Bad) -> Bad = { left, _ -> left }

        attempt<Bad, String> {
            parZipOrAccumulate(worst, { "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }) { a, b, c, d, e, f ->
                a + b + c + d + e + f
            }
        } shouldBeRight "abcdef"

        attempt<Bad, String> {
            parZipOrAccumulate(
                worst, { "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" },
            ) { a, b, c, d, e, f, g -> a + b + c + d + e + f + g }
        } shouldBeRight "abcdefg"

        attempt<Bad, String> {
            parZipOrAccumulate(
                worst, { "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" }, { "h" },
            ) { a, b, c, d, e, f, g, h -> a + b + c + d + e + f + g + h }
        } shouldBeRight "abcdefgh"

        attempt<Bad, String> {
            parZipOrAccumulate(
                worst, { "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" }, { "h" }, { "i" },
            ) { a, b, c, d, e, f, g, h, i -> a + b + c + d + e + f + g + h + i }
        } shouldBeRight "abcdefghi"
    }
}
