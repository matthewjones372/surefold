package dev.surefold.guides

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File

/** The Kotlin compiler in the test JVM, so what compiles and what does not are both assertions. */
internal class EmbeddedKotlin(private val workspace: File) {

    /** The exit code and the errors reported, against the library on the test's own classpath. */
    fun compile(source: String): Pair<ExitCode, List<String>> {
        val file = File(workspace, "Fixture.kt").apply { writeText(source) }
        val errors = mutableListOf<String>()
        val collector = object : MessageCollector {
            override fun clear() = errors.clear()
            override fun hasErrors() = errors.isNotEmpty()
            override fun report(
                severity: CompilerMessageSeverity,
                message: String,
                location: CompilerMessageSourceLocation?,
            ) {
                if (severity.isError) errors += message
            }
        }

        val exit = K2JVMCompiler().exec(
            collector,
            Services.EMPTY,
            K2JVMCompilerArguments().apply {
                freeArgs = listOf(file.absolutePath)
                classpath = System.getProperty("java.class.path")
                destination = File(workspace, "out").absolutePath
                noStdlib = true
                noReflect = true
                // The library on the classpath is built for 25; without this
                // the compiler defaults to 1.8 and every fixture fails for a
                // reason that has nothing to do with what is being asserted.
                jvmTarget = "25"
            },
        )

        return exit to errors
    }
}
