package dev.surefold.junit.testkit

import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.TestExecutionResult.Status
import org.junit.platform.testkit.engine.Event
import org.junit.platform.testkit.engine.EventType
import org.junit.platform.testkit.engine.Events
import org.opentest4j.AssertionFailedError

/** The display names of these events' tests, in the order the events happened. */
fun Events.names(): List<String> = map { it.testDescriptor.displayName }.toList()

/**
 * The display names of everything that finished, tests and containers, in the order they finished. A skipped test
 * never finishes, so it is absent.
 */
fun Events.finishOrder(): List<String> =
    list().filter { it.type == EventType.FINISHED }.map { it.testDescriptor.displayName }

/** Passes if exactly [tests] succeeded, in any order; a miss shows how each of the others ended, and why. */
infix fun Events.shouldHaveSucceeded(tests: Iterable<String>) = shouldHaveEnded(Ended.Succeeded, tests)

/** Passes if exactly [tests] succeeded, in any order. */
fun Events.shouldHaveSucceeded(vararg tests: String) = shouldHaveSucceeded(tests.asList())

/** Passes if exactly [tests] failed, in any order; a miss shows how each of the others ended. */
infix fun Events.shouldHaveFailed(tests: Iterable<String>) = shouldHaveEnded(Ended.Failed, tests)

/** Passes if exactly [tests] failed, in any order. */
fun Events.shouldHaveFailed(vararg tests: String) = shouldHaveFailed(tests.asList())

/** Passes if exactly [tests] were skipped, in any order; a miss shows how each of the others ended. */
infix fun Events.shouldHaveSkipped(tests: Iterable<String>) = shouldHaveEnded(Ended.Skipped, tests)

/** Passes if exactly [tests] were skipped, in any order. */
fun Events.shouldHaveSkipped(vararg tests: String) = shouldHaveSkipped(tests.asList())

/**
 * Passes if exactly the tests in [reasons] were skipped, each for its reason; a miss shows how each of the others
 * ended, and each skipped for another reason with the reason it gave.
 */
infix fun Events.shouldHaveSkipped(reasons: Map<String, String>) {
    val outcomes = outcomes()
    val actual = skipReasons()
    if (actual == reasons) return
    val misses = (reasons.keys - actual.keys).sorted().map { "  \"$it\" ${outcomes[it]?.said ?: "did not run"}" } +
        reasons.keys.intersect(actual.keys).sorted().filter { reasons[it] != actual[it] }
            .map { "  \"$it\" was skipped for \"${actual[it]}\", not \"${reasons[it]}\"" } +
        (actual.keys - reasons.keys).sorted().map {
            "  \"$it\" was skipped too, for \"${actual[it]}\", and was not named"
        }
    throw AssertionFailedError(
        "expected exactly these tests to have been skipped, for these reasons: ${reasons.toSortedMap()}\nbut:\n" +
            misses.joinToString("\n"),
        reasons.toSortedMap(),
        actual.toSortedMap(),
    )
}

/** What each skipped test was skipped for, by name; a skip that gave no reason reads as "". */
fun Events.skipReasons(): Map<String, String> =
    outcomes().filterValues { it.ended == Ended.Skipped }.mapValues { (_, outcome) -> outcome.reason.orEmpty() }

/** Each of these events' tests' tags, by name, as the names given to `tag`; a test with none has an empty set. */
fun Events.tags(): Map<String, Set<String>> =
    list().associate { event ->
        event.testDescriptor.displayName to event.testDescriptor.tags.map { it.name }.toSet()
    }

/** What each failed test threw, by name. */
fun Events.failures(): Map<String, Throwable> =
    outcomes().filterValues { it.ended == Ended.Failed }.mapValues { (_, outcome) -> checkNotNull(outcome.thrown) }

/** What [test] threw; throws, saying how it ended instead, if it passed, was skipped or did not run. */
fun Events.failureOf(test: String): Throwable {
    val outcome = outcomes()[test] ?: throw AssertionFailedError(
        "no test named \"$test\" ran; these did: ${outcomes().keys.sorted()}",
    )
    return outcome.thrown ?: throw AssertionFailedError("\"$test\" did not fail: it ${outcome.said}")
}

private enum class Ended(val toHave: String, val did: String) {
    Succeeded("succeeded", "succeeded"),
    Failed("failed", "failed"),
    Skipped("been skipped", "was skipped"),
}

private class Outcome(val ended: Ended?, val said: String, val thrown: Throwable? = null, val reason: String? = null)

/** How each test ended, by name; the last event wins for a test that ran more than once. */
private fun Events.outcomes(): Map<String, Outcome> =
    filter { it.type == EventType.FINISHED || it.type == EventType.SKIPPED }.toList()
        .associate { it.testDescriptor.displayName to outcomeOf(it) }

private fun outcomeOf(event: Event): Outcome {
    if (event.type == EventType.SKIPPED) {
        val reason = event.getPayload(String::class.java).orElse(null)
        return Outcome(Ended.Skipped, "was skipped: ${reason ?: "no reason given"}", reason = reason)
    }
    val result = event.getRequiredPayload(TestExecutionResult::class.java)
    val thrown = result.throwable.orElse(null)
    val why = thrown?.let { ": " + (it.message?.lines()?.firstOrNull { line -> line.isNotBlank() } ?: it.toString()) }
    return when (result.status) {
        Status.SUCCESSFUL -> Outcome(Ended.Succeeded, "succeeded")
        Status.FAILED -> Outcome(Ended.Failed, "failed${why.orEmpty()}", thrown)
        Status.ABORTED -> Outcome(null, "was aborted${why.orEmpty()}", thrown)
    }
}

private fun Events.shouldHaveEnded(ended: Ended, tests: Iterable<String>) {
    val outcomes = outcomes()
    val expected = tests.sorted()
    val actual = outcomes.filterValues { it.ended == ended }.keys.sorted()
    if (actual == expected) return
    val misses = (expected - actual.toSet()).map { "  \"$it\" ${outcomes[it]?.said ?: "did not run"}" } +
        (actual - expected.toSet()).map { "  \"$it\" ${ended.did} too, and was not named" }
    throw AssertionFailedError(
        "expected exactly these tests to have ${ended.toHave}: $expected\nbut:\n" +
            misses.joinToString("\n"),
        expected,
        actual,
    )
}
