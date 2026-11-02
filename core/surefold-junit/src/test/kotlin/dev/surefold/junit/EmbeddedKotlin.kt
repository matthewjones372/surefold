package dev.surefold.junit

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File

/** The Kotlin compiler in the test JVM, set as this module's build sets it, so what does not compile is a claim. */
internal class EmbeddedKotlin(private val workspace: File) {

    /** The exit code, and the errors and warnings reported, against this module on the test's own classpath. */
    fun compile(source: String): Pair<ExitCode, List<String>> {
        val file = File(workspace, "Fixture.kt").apply { writeText(source) }
        val reported = mutableListOf<String>()
        val collector = object : MessageCollector {
            override fun clear() = reported.clear()
            override fun hasErrors() = reported.isNotEmpty()
            override fun report(
                severity: CompilerMessageSeverity,
                message: String,
                location: CompilerMessageSourceLocation?,
            ) {
                if (severity.isError || severity.isWarning) reported += message
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
                // The classpath is built for 25, and the compiler would otherwise default to 1.8.
                jvmTarget = "25"
                returnValueChecker = "check"
                allWarningsAsErrors = true
            },
        )

        return exit to reported
    }
}
