package dev.surefold.app.compiler

import org.jetbrains.kotlin.config.KotlinCompilerVersion
import java.util.Properties

/**
 * Whether this plugin is running in the compiler it was built against.
 *
 * Everything it touches — a checker's signature, a diagnostic's constructor, how a symbol is
 * resolved — is compiler API with no stability promise, and a plugin built for one minor version
 * and loaded into another does not fail in a way anybody sees: the compiler may report a linkage
 * error, and the editor drops the checker and says nothing. Comparing the two is the difference
 * between a sentence and a silence.
 *
 * Major and minor, because that is what the compiler's own API moves on; a patch release has never
 * been what breaks this, and refusing one would turn every upgrade into a wait for a surefold release.
 */
internal fun builtForThisCompiler(): Boolean = series(builtFor) == series(KotlinCompilerVersion.VERSION)

/** What to say when it is not, in the words of the give-up the reader already has. */
internal fun versionMismatch(): String =
    "it was built for Kotlin $builtFor and this compiler is ${KotlinCompilerVersion.VERSION}"

private fun series(version: String): String = version.split('.').take(2).joinToString(".")

/** Written into this jar by its own build; see `surefold-app-compiler/build.gradle.kts`. */
private val builtFor: String by lazy {
    val read = Properties().apply {
        SurefoldDiagnostics::class.java.getResourceAsStream("/surefold-compiler.properties")?.use { load(it) }
    }
    read.getProperty("kotlin") ?: error("surefold-app-compiler was built without surefold-compiler.properties")
}
