package dev.surefold.fail

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeSameInstanceAs
import dev.surefold.assert.shouldThrow
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

class EitherTest {

    private val right: Either<String, Int> = 1.right()
    private val left: Either<String, Int> = "e".left()

    @Test
    fun `fold takes the side the value is on`() {
        right.fold({ "left $it" }, { "right $it" }) shouldBe "right 1"
        left.fold({ "left $it" }, { "right $it" }) shouldBe "left e"
    }

    @Test
    fun `map and flatMap touch only a right`() {
        right.map { it + 1 } shouldBe 2.right()
        left.map { it + 1 } shouldBe left
        right.flatMap { "stop".left() } shouldBe "stop".left()
        left.flatMap { 3.right() } shouldBe left
    }

    @Test
    fun `mapLeft touches only a left`() {
        left.mapLeft { it.length } shouldBe 1.left()
        right.mapLeft { it.length } shouldBe right
    }

    @Test
    fun `the getters answer null on the other side`() {
        right.getOrNull() shouldBe 1
        right.leftOrNull() shouldBe null
        left.getOrNull() shouldBe null
        left.leftOrNull() shouldBe "e"
        left.getOrElse { it.length } shouldBe 1
        right.isRight() shouldBe true
        left.isLeft() shouldBe true
    }

    @Test
    fun `onLeft and onRight run only for their own side`() {
        val seen = StringBuilder()
        right.onLeft { seen.append("L") }.onRight { seen.append("R$it") }
        left.onLeft { seen.append("L$it") }.onRight { seen.append("R") }
        seen.toString() shouldBe "R1Le"
    }

    @Test
    fun `swap and merge`() {
        right.swap() shouldBe 1.left()
        "same".left().merge() shouldBe "same"
    }

    @Test
    fun `catch answers with what the block returned, or what it threw`() {
        Either.catch { 1 } shouldBe 1.right()
        val boom = IllegalStateException("boom")
        (Either.catch { throw boom }.leftOrNull()) shouldBeSameInstanceAs boom
    }

    @Test
    fun `catch lets a fatal throw and a fail go on up`() {
        shouldThrow<CancellationException> { Either.catch { throw CancellationException("stop") } }
        attempt<String, Int> { Either.catch { fail("declared") }.getOrElse { 0 } } shouldBe "declared".left()
    }
}
