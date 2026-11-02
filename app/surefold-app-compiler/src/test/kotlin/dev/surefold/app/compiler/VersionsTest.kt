package dev.surefold.app.compiler

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContain
import dev.surefold.assert.withClue
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.junit.jupiter.api.Test

/**
 * The version guard, tested where it can be: the compiler on this module's own test classpath is
 * the one it was built against, so the answer here is the answer a consumer on the same Kotlin gets.
 */
class VersionsTest {

    @Test
    fun `the compiler this was built against is the one it recognises`() {
        builtForThisCompiler() shouldBe true
    }

    @Test
    fun `the sentence names both versions, because either could be the one to change`() {
        withClue("a reader told only 'wrong version' has to go and find out which two") {
            versionMismatch() shouldContain KotlinCompilerVersion.VERSION
        }
    }

    @Test
    fun `the jar says what it was built for, or the build that made it was wrong`() {
        versionMismatch() shouldContain "built for Kotlin"
    }
}
