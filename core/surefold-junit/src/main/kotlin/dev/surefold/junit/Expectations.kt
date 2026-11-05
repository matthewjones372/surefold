package dev.surefold.junit

import dev.surefold.assert.FailureSeam
import dev.surefold.assert.failureAt
import dev.surefold.assert.mismatchAt
import dev.surefold.assert.withClue as clued
import dev.surefold.test.Verdict
import kotlin.time.Duration
import org.opentest4j.AssertionFailedError
import org.opentest4j.MultipleFailuresError

/**
 * What a test's checks found: every failure is kept, and none is thrown until the test has returned. Each check made
 * with [expect] and the matchers beside it is recorded as the test runs, and the record is the test's result.
 */
@PublishedApi
internal class Expectations(
    internal val failures: List<AssertionFailedError>,
    internal val checked: Int,
) : Verdict {

    /** Whether every check held, for [eventually] to stop trying. */
    override val held: Boolean
        get() = failures.isEmpty()

    /** The same failures, recorded, each saying first that [eventually] gave up after [tries] tries in [elapsed]. */
    override fun gaveUp(tries: Long, elapsed: Duration): Expectations =
        recordedIfInTest(Expectations(failures.map { it.headed("gave up after $tries tries in $elapsed") }, checked))

    /** The one failure, or all of them as one error; a story marks a step failed with it. */
    internal val failure: Throwable? by lazy {
        when (failures.size) {
            0 -> null
            1 -> failures.single()
            else -> MultipleFailuresError(null, failures).also { it.stackTrace = failures.first().stackTrace }
        }
    }

    /** Both sides' failures, so neither hides the other's. Each side is already recorded; this is not again. */
    operator fun plus(other: Expectations): Expectations =
        Expectations(failures + other.failures, checked + other.checked)

    /**
     * Throws nothing if every expectation held, the one failure alone, or all of them as one error. A test that checked
     * nothing fails too.
     */
    internal fun verify(test: String) {
        if (checked == 0) throw AssertionFailedError("$test: no checks were made")
        val heading = "$test: ${failures.size} of $checked expectations failed"
        when (failures.size) {
            0 -> Unit
            1 -> throw failures.single().headed(heading)
            else -> throw MultipleFailuresError(heading, failures)
        }
    }
}

/** Holds if [condition] is true; otherwise fails, naming this line of the test and quoting its source. */
fun expect(condition: Boolean) {
    if (condition) held() else failedHere("expected: true\nbut was:  false")
}

/** Holds if [condition] is true; otherwise fails with [message], naming this line. The power-assert plugin's target. */
fun expect(condition: Boolean, message: () -> String) {
    if (condition) held() else failedHere(message().trim(), quoting = false)
}

/**
 * One check of [block], for a library that checks by throwing: holds if [block] returns, and fails if it throws an
 * [AssertionError], naming this line with what it threw. Anything else it throws goes on up, as it would without.
 */
fun holds(block: () -> Unit) {
    try {
        block()
        held()
    } catch (thrown: AssertionError) {
        thrownHere(thrown)
    }
}

/** [block]'s checks, each failure in it saying [clue] first. Blocks nest, the outermost clue first. */
fun withClue(clue: Any?, block: () -> Unit): Unit = clued(clue, block)

/** The same, with the clue built only if a check in [block] fails. */
fun withClue(clue: () -> Any?, block: () -> Unit): Unit = clued(clue, block)

/** Checks [check] on every element, keeping every element's failures. On no elements, it checks nothing. */
inline fun <T> Iterable<T>.forAll(check: (T) -> Unit) {
    forEach(check)
}

/**
 * Holds if any of [alternatives] does, each run as a check of its own whose checks count only here; otherwise fails
 * with every alternative's failures, each saying first that none held.
 */
fun anyOf(vararg alternatives: () -> Unit) {
    val tried = alternatives.map { recording(it).second }
    when {
        tried.any { it.held } -> held()
        else -> recorded(Expectations(tried.flatMap { it.failures }.map { it.headed("none of these held") }, 1))
    }
}

/** Holds if [check] fails, its checks counting only here; fails at this line if [check] holds. */
fun not(check: () -> Unit) {
    if (recording(check).second.held) failedHere("expected: a failure\nbut was:  a pass") else held()
}

/** A check that passed, recorded: for [expect] and the matchers that take a lambda. */
@PublishedApi internal fun held(): Expectations = recorded(passed(1))

/** No checks at all, for a record to start from. */
internal fun nothingChecked(): Expectations = passed(0)

internal fun passed(checked: Int) = Expectations(emptyList(), checked)

/** The same failure, with [heading] above its message and the same expected and actual. */
private fun AssertionFailedError.headed(heading: String): AssertionFailedError {
    val text = "$heading\n$message"
    val headed =
        when {
            isExpectedDefined -> AssertionFailedError(text, expected, actual, cause)
            else -> AssertionFailedError(text, cause)
        }
    return headed.also { it.stackTrace = stackTrace }
}

// This module's frames that build a failure, so a failure's first frame is the test's.
private val own =
    setOf(
        Expectations::class.java.name,
        Expectations::class.java.name + "Kt",
        Expectations::class.java.packageName + ".MatchersKt",
        Expectations::class.java.packageName + ".CollectionMatchersKt",
    )

@OptIn(FailureSeam::class)
internal fun failedHere(message: String, quoting: Boolean = true): Expectations =
    recorded(Expectations(listOf(failureAt(message, skipping = { it.className in own }, quoting)), 1))

/** [thrown]'s message, and its expected and actual where it has them, at the test's line; [thrown] is the cause. */
@OptIn(FailureSeam::class)
private fun thrownHere(thrown: AssertionError): Expectations {
    val failure =
        failureAt(thrown.message.orEmpty().trim(), skipping = { it.className in own }) { located ->
            if (thrown is AssertionFailedError && thrown.isExpectedDefined) {
                AssertionFailedError(located, thrown.expected?.value, thrown.actual?.value, thrown)
            } else {
                AssertionFailedError(located, thrown)
            }
        }
    return recorded(Expectations(listOf(failure), 1))
}

@OptIn(FailureSeam::class)
internal fun mismatchedHere(
    expected: Any?,
    actual: Any?,
    expectedText: String? = null,
    cause: Throwable? = null,
): Expectations =
    recorded(
        Expectations(listOf(mismatchAt(expected, actual, skipping = { it.className in own }, expectedText, cause)), 1),
    )
