package dev.surefold.compiler

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContain
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.junit.jupiter.api.Test

/** The version guard, where it can be tested: the compiler on this test classpath is the one it was built for. */
class VersionsTest {

    @Test
    fun `the compiler this was built against is the one it recognises`() {
        builtForThisCompiler() shouldBe true
    }

    @Test
    fun `the warning names both versions`() {
        versionMismatch() shouldContain "built for Kotlin"
        versionMismatch() shouldContain KotlinCompilerVersion.VERSION
    }
}
