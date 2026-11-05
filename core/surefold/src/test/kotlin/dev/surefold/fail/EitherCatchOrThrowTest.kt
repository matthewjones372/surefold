package dev.surefold.fail

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeSameInstanceAs
import dev.surefold.assert.shouldThrow
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Test

class EitherCatchOrThrowTest {
    @Test
    fun `what the block returns is on the right`() {
        Either.catchOrThrow<IOException, Int> { 1 } shouldBe 1.right()
    }

    @Test
    fun `a thrown T is on the left as the same instance`() {
        val thrown = IOException("io")
        val answer: Either<IOException, Int> = Either.catchOrThrow { throw thrown }
        answer.leftOrNull() shouldBeSameInstanceAs thrown
    }

    @Test
    fun `a subclass of T is on the left`() {
        val thrown = FileNotFoundException("missing")
        Either.catchOrThrow<IOException, Int> { throw thrown }.leftOrNull() shouldBeSameInstanceAs thrown
    }

    @Test
    fun `anything else non-fatal is rethrown as the same instance`() {
        val thrown = IllegalStateException("not io")
        shouldThrow<IllegalStateException> {
            Either.catchOrThrow<IOException, Int> { throw thrown }
        } shouldBeSameInstanceAs thrown
    }

    @Test
    fun `fatals go up even when T is Throwable`() {
        listOf(OutOfMemoryError("oom"), InterruptedException("stop"), CancellationException("cancel")).forEach {
            shouldThrow<Throwable> { Either.catchOrThrow<Throwable, Int> { throw it } } shouldBeSameInstanceAs it
        }
    }

    @Test
    fun `a fail inside reaches its attempt even when T is Throwable`() {
        attempt<String, Int> {
            Either.catchOrThrow<Throwable, Int> { fail("declared") }.getOrElse { 0 }
        } shouldBe "declared".left()
    }
}
