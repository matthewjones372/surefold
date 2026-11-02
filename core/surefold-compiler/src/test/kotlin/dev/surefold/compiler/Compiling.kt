package dev.surefold.compiler

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

/** What compiling one snippet with the plugin loaded said: whether it compiled, and every error and warning. */
internal data class Compiled(val exit: ExitCode, val messages: List<String>) {
    /** The messages that are this plugin's, by the prefix its renderer gives them. */
    val surefold: List<String> get() = messages.filter { "surefold:" in it }
}

/** [source] compiled against surefold, with the plugin loaded from its classes as a consumer's compiler loads it. */
internal fun compile(source: String): Compiled {
    val dir = createTempDirectory("surefold-compiler")
    val file = dir.resolve("Snippet.kt").apply { writeText(source) }
    val said = Said()
    val arguments = K2JVMCompilerArguments().apply {
        freeArgs = listOf(file.toString())
        destination = dir.resolve("out").toString()
        classpath = System.getProperty("java.class.path")
        noStdlib = true
        noReflect = true
        jvmTarget = "25"
        pluginClasspaths = System.getProperty("surefold.compiler.plugin").split(File.pathSeparator).toTypedArray()
    }
    val exit = K2JVMCompiler().exec(said, Services.EMPTY, arguments)
    dir.toFile().deleteRecursively()
    return Compiled(exit, said.messages)
}

private class Said : MessageCollector {
    val messages = mutableListOf<String>()

    override fun clear() = messages.clear()

    override fun hasErrors(): Boolean = messages.any { it.startsWith("ERROR") }

    override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
        if (severity.isError || severity.isWarning) messages += "$severity: $message"
    }
}
