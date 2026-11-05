package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeLeft
import dev.surefold.assert.shouldBeRight
import dev.surefold.assert.shouldThrow
import dev.surefold.fail.NonEmptyList
import dev.surefold.fail.attempt
import dev.surefold.fail.nonEmptyListOf
import org.junit.jupiter.api.Test

class ZipOrAccumulateTest {

    @Test
    fun `two failing branches answer with a NonEmptyList of both, in branch order`() {
        attempt<NonEmptyList<Bad>, Int> {
            zipOrAccumulate(
                { fail(Bad("first")) },
                { fail(Bad("second")) },
            ) { _: Int, _: Int ->
                0
            }
        } shouldBeLeft nonEmptyListOf(Bad("first"), Bad("second"))
    }

    @Test
    fun `every branch runs on the calling thread, after the one before it`() {
        val caller = Thread.currentThread()
        val ran = mutableListOf<String>()

        attempt<NonEmptyList<Bad>, Int> {
            zipOrAccumulate(
                {
                    ran += "a"
                    Thread.currentThread() shouldBe caller
                    fail(Bad("a"))
                },
                {
                    ran += "b"
                    Thread.currentThread() shouldBe caller
                    1
                },
            ) { _: Int, b: Int ->
                b
            }
        } shouldBeLeft nonEmptyListOf(Bad("a"))
        ran shouldBe listOf("a", "b")
    }

    @Test
    fun `a throw ends the call where it happens and is rethrown as the same instance`() {
        val boom = Boom()
        var secondRan = false

        val thrown =
            shouldThrow<Boom> {
                attempt<NonEmptyList<Bad>, Int> {
                    zipOrAccumulate(
                        { throw boom },
                        { secondRan = true },
                    ) { _: Int, _: Unit ->
                        0
                    }
                }
            }

        (thrown === boom) shouldBe true
        secondRan shouldBe false
    }

    @Test
    fun `a null error is kept like any other`() {
        attempt<NonEmptyList<Bad?>, Int> {
            zipOrAccumulate({ fail(null) }, { fail(Bad("b")) }) { _: Int, _: Int -> 0 }
        } shouldBeLeft nonEmptyListOf(null, Bad("b"))
    }

    @Test
    fun `combine folds the failures into the single error the scope declares`() {
        attempt<Bad, Int> {
            zipOrAccumulate(
                { left, right -> Bad(left.why + right.why) },
                { fail(Bad("a")) },
                { 1 },
                { fail(Bad("c")) },
            ) { _: Int, _: Int, _: Int ->
                0
            }
        } shouldBeLeft Bad("ac")
    }

    @Test
    fun `zipOrAccumulate combines two to five branches`() {
        attempt<NonEmptyList<Bad>, String> {
            zipOrAccumulate({ "a" }, { "b" }) { a, b -> a + b }
        } shouldBeRight "ab"
        attempt<NonEmptyList<Bad>, String> {
            zipOrAccumulate({ "a" }, { "b" }, { "c" }) { a, b, c -> a + b + c }
        } shouldBeRight "abc"
        attempt<NonEmptyList<Bad>, String> {
            zipOrAccumulate({ "a" }, { "b" }, { "c" }, { "d" }) { a, b, c, d -> a + b + c + d }
        } shouldBeRight "abcd"
        attempt<NonEmptyList<Bad>, String> {
            zipOrAccumulate({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }) { a, b, c, d, e -> a + b + c + d + e }
        } shouldBeRight "abcde"
        attempt<NonEmptyList<Bad>, String> {
            zipOrAccumulate(
                { fail(Bad("1")) },
                { "b" },
                { fail(Bad("3")) },
                { "d" },
                { fail(Bad("5")) },
            ) { a: String, b, c: String, d, e: String ->
                a + b + c + d + e
            }
        } shouldBeLeft nonEmptyListOf(Bad("1"), Bad("3"), Bad("5"))
    }

    @Test
    fun `the combine form combines two to five branches`() {
        val worst: (Bad, Bad) -> Bad = { left, _ -> left }

        attempt<Bad, String> { zipOrAccumulate(worst, { "a" }, { "b" }) { a, b -> a + b } } shouldBeRight "ab"
        attempt<Bad, String> {
            zipOrAccumulate(worst, { "a" }, { "b" }, { "c" }) { a, b, c -> a + b + c }
        } shouldBeRight "abc"
        attempt<Bad, String> {
            zipOrAccumulate(worst, { "a" }, { "b" }, { "c" }, { "d" }) { a, b, c, d -> a + b + c + d }
        } shouldBeRight "abcd"
        attempt<Bad, String> {
            zipOrAccumulate(worst, { "a" }, { "b" }, { "c" }, { "d" }, { "e" }) { a, b, c, d, e ->
                a + b + c + d + e
            }
        } shouldBeRight "abcde"
        attempt<Bad, String> {
            zipOrAccumulate(worst, { "a" }, { fail(Bad("b")) }, { "c" }, { "d" }, { fail(Bad("e")) }) {
                a,
                b: String,
                c,
                d,
                e: String ->
                a + b + c + d + e
            }
        } shouldBeLeft Bad("b")
    }

    @Test
    fun `zipOrAccumulate combines six to nine branches`() {
        attempt<NonEmptyList<Bad>, String> {
            zipOrAccumulate({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }) { a, b, c, d, e, f ->
                a + b + c + d + e + f
            }
        } shouldBeRight "abcdef"
        attempt<NonEmptyList<Bad>, String> {
            zipOrAccumulate({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" }) { a, b, c, d, e, f, g ->
                a + b + c + d + e + f + g
            }
        } shouldBeRight "abcdefg"
        attempt<NonEmptyList<Bad>, String> {
            zipOrAccumulate({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" }, { "h" }) {
                a,
                b,
                c,
                d,
                e,
                f,
                g,
                h ->
                a + b + c + d + e + f + g + h
            }
        } shouldBeRight "abcdefgh"
        attempt<NonEmptyList<Bad>, String> {
            zipOrAccumulate({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" }, { "h" }, { "i" }) {
                a,
                b,
                c,
                d,
                e,
                f,
                g,
                h,
                i ->
                a + b + c + d + e + f + g + h + i
            }
        } shouldBeRight "abcdefghi"
    }

    @Test
    fun `six to nine branches answer their failures in branch order`() {
        attempt<NonEmptyList<Bad>, Int> {
            zipOrAccumulate({ fail(Bad("1")) }, { "b" }, { "c" }, { "d" }, { "e" }, { fail(Bad("6")) }) {
                _: String,
                _,
                _,
                _,
                _,
                _: String ->
                0
            }
        } shouldBeLeft nonEmptyListOf(Bad("1"), Bad("6"))
        attempt<NonEmptyList<Bad>, Int> {
            zipOrAccumulate({ "a" }, { fail(Bad("2")) }, { "c" }, { "d" }, { "e" }, { "f" }, { fail(Bad("7")) }) {
                _,
                _: String,
                _,
                _,
                _,
                _,
                _: String ->
                0
            }
        } shouldBeLeft nonEmptyListOf(Bad("2"), Bad("7"))
        attempt<NonEmptyList<Bad>, Int> {
            zipOrAccumulate(
                { "a" },
                { "b" },
                { fail(Bad("3")) },
                { "d" },
                { "e" },
                { "f" },
                { "g" },
                { fail(Bad("8")) },
            ) { _, _, _: String, _, _, _, _, _: String ->
                0
            }
        } shouldBeLeft nonEmptyListOf(Bad("3"), Bad("8"))
        attempt<NonEmptyList<Bad>, Int> {
            zipOrAccumulate(
                { "a" },
                { "b" },
                { "c" },
                { fail(Bad("4")) },
                { "e" },
                { "f" },
                { "g" },
                { "h" },
                { fail(Bad("9")) },
            ) { _, _, _, _: String, _, _, _, _, _: String ->
                0
            }
        } shouldBeLeft nonEmptyListOf(Bad("4"), Bad("9"))
    }

    @Test
    fun `the combine form combines six to nine branches`() {
        val both: (Bad, Bad) -> Bad = { left, right -> Bad(left.why + right.why) }

        attempt<Bad, Int> {
            zipOrAccumulate(both, { fail(Bad("1")) }, { 2 }, { 3 }, { 4 }, { 5 }, { fail(Bad("6")) }) {
                _: Int,
                _,
                _,
                _,
                _,
                _: Int ->
                0
            }
        } shouldBeLeft Bad("16")
        attempt<Bad, Int> {
            zipOrAccumulate(both, { 1 }, { 2 }, { 3 }, { 4 }, { 5 }, { 6 }, { 7 }) { a, b, c, d, e, f, g ->
                a + b + c + d + e + f + g
            }
        } shouldBeRight 28
        attempt<Bad, Int> {
            zipOrAccumulate(both, { 1 }, { 2 }, { 3 }, { 4 }, { 5 }, { 6 }, { 7 }, { fail(Bad("8")) }) {
                _,
                _,
                _,
                _,
                _,
                _,
                _,
                _: Int ->
                0
            }
        } shouldBeLeft Bad("8")
        attempt<Bad, Int> {
            zipOrAccumulate(both, { 1 }, { 2 }, { 3 }, { 4 }, { 5 }, { 6 }, { 7 }, { 8 }, { 9 }) {
                a,
                b,
                c,
                d,
                e,
                f,
                g,
                h,
                i ->
                a + b + c + d + e + f + g + h + i
            }
        } shouldBeRight 45
    }
}
