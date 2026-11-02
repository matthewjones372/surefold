package dev.surefold.compiler

import org.jetbrains.kotlin.config.KotlinCompilerVersion
import java.util.Properties

/**
 * Whether this plugin is running in the compiler it was built against. Its checkers are compiler API with no
 * stability promise, and one loaded into another minor version fails where nobody sees it, the editor least of all.
 * Major and minor, as `surefold-app-compiler` compares them: a patch release has never been what breaks a plugin.
 */
internal fun builtForThisCompiler(): Boolean = series(builtFor) == series(KotlinCompilerVersion.VERSION)

/** What the compiler says when it is not, naming both versions, since either could be the one to change. */
internal fun versionMismatch(): String =
    "surefold-compiler was built for Kotlin $builtFor and this compiler is ${KotlinCompilerVersion.VERSION}, " +
        "so its checks are off; use the surefold release built for this Kotlin"

private fun series(version: String): String = version.split('.').take(2).joinToString(".")

/** Written into this jar by its own build; see `surefold-compiler/build.gradle.kts`. */
private val builtFor: String by lazy {
    val read = Properties().apply {
        ForkDiagnostics::class.java.getResourceAsStream("/surefold-forks.properties")?.use { load(it) }
    }
    read.getProperty("kotlin") ?: error("surefold-compiler was built without surefold-forks.properties")
}
