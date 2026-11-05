package dev.surefold.assert

import dev.surefold.fail.Either
import java.io.File
import org.opentest4j.AssertionFailedError

// The file facades of this module's own checks, whose frames a failure leaves out.
private const val OWN = "dev.surefold.assert."

// This file's facade, left out of a failure built for another module.
private const val SELF = OWN + "MismatchKt"

// Where a test's source is, relative to the directory Gradle runs a module's tests in.
private val sourceRoots = listOf("src/test/kotlin", "src/test/java", "src/testFixtures/kotlin")

/** A seam for surefold's own modules that report a failed check as this module does. */
@RequiresOptIn("A seam for surefold's own modules that report a failed check as this module does.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class FailureSeam

/**
 * Fails with what was expected, what was found, the line of the test that checked it and that line's source. The stack
 * starts at the test's own frame, so the line a runner or an IDE names first is the check, not this module.
 */
@PublishedApi
@OptIn(FailureSeam::class)
internal fun mismatch(expected: Any?, actual: Any?, expectedText: String? = null, cause: Throwable? = null): Nothing =
    throw mismatchAt(expected, actual, ::isOwn, expectedText, cause)

private fun isOwn(frame: StackTraceElement): Boolean = frame.className.startsWith(OWN) && frame.className.endsWith("Kt")

/**
 * The failure [mismatch] throws, built at the first frame not [skipping] and returned. [expected] and [actual] are
 * carried for an IDE's diff unless [expectedText] stands in for what was expected.
 */
@FailureSeam
fun mismatchAt(
    expected: Any?,
    actual: Any?,
    skipping: (StackTraceElement) -> Boolean,
    expectedText: String? = null,
    cause: Throwable? = null,
): AssertionFailedError {
    val (shownExpected, shownActual) = expectedText?.let { it to show(actual) } ?: shownApart(expected, actual)
    return failureAt("expected: $shownExpected\nbut was:  $shownActual", skipping) { message ->
        when (expectedText) {
            null -> AssertionFailedError(message, expected, actual, cause)
            else -> AssertionFailedError(message, cause)
        }
    }
}

/**
 * [failure] with the clues in force, [message], the line of the first frame not [skipping] and, if [quoting], that
 * line's source, its stack starting at that frame. That frame is the test's own when [skipping] matches every frame of
 * the module checking.
 */
@FailureSeam
fun failureAt(
    message: String,
    skipping: (StackTraceElement) -> Boolean,
    quoting: Boolean = true,
    failure: (String) -> AssertionFailedError = ::AssertionFailedError,
): AssertionFailedError {
    val frames =
        Thread.currentThread().stackTrace.dropWhile { frame ->
            frame.className == Thread::class.java.name || frame.className == SELF || skipping(frame)
        }
    val located = buildString {
        cluesNow().forEach { append(it).append('\n') }
        append(message)
        frames.firstOrNull()?.let { site ->
            append("\nat ${site.fileName}:${site.lineNumber}")
            if (quoting) sourceLine(site)?.let { append("\n    $it") }
        }
    }
    return failure(located).also { it.stackTrace = frames.toTypedArray() }
}

/** Both values as they print, with their types added when the two would otherwise print alike. */
private fun shownApart(expected: Any?, actual: Any?): Pair<String, String> =
    if (show(expected) != show(actual) || typeOf(expected) == typeOf(actual)) {
        show(expected) to show(actual)
    } else {
        "${show(expected)} of ${typeOf(expected)}" to "${show(actual)} of ${typeOf(actual)}"
    }

private fun show(value: Any?): String = value.toString()

/** The type that tells two alike-printing values apart: an Either's is the type of what it holds. */
private fun typeOf(value: Any?): String =
    when (value) {
        is Either.Right<*> -> typeOf(value.value)
        is Either.Left<*> -> typeOf(value.value)
        null -> "null"
        else -> value::class.qualifiedName ?: value.javaClass.name
    }

/** The trimmed source of [site]'s line, when the test's source is where Gradle runs it from. */
private fun sourceLine(site: StackTraceElement): String? {
    val file = site.fileName ?: return null
    val directory = site.className.substringBeforeLast('.', "").replace('.', '/')
    return sourceRoots
        .asSequence()
        .map { File(it, "$directory/$file") }
        .firstOrNull(File::isFile)
        ?.useLines { lines -> lines.drop(site.lineNumber - 1).firstOrNull() }
        ?.trim()
}
