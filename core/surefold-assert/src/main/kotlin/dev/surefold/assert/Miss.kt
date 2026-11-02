package dev.surefold.assert

/**
 * What a check that did not hold found: the value wanted and the value seen, or [expectedText] standing in for the
 * value wanted, and what was thrown when that is why. Each check is written once, as a function answering a [Miss] or
 * null; this module throws the [Miss], and surefold-junit keeps it as an expectation.
 */
@FailureSeam
data class Miss(val expected: Any?, val actual: Any?, val expectedText: String? = null, val cause: Throwable? = null)

/** Throws [miss] as this module's failure, at the line of the test that checked. */
@PublishedApi
@OptIn(FailureSeam::class)
internal fun thrown(miss: Miss): Nothing = mismatch(miss.expected, miss.actual, miss.expectedText, miss.cause)
