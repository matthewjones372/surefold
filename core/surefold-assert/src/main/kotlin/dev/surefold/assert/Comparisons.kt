@file:OptIn(FailureSeam::class)

package dev.surefold.assert

/** Asserts this is less than [bound], and returns it. */
infix fun <T : Comparable<T>> T.shouldBeLessThan(bound: T): T = also { notLessThan(bound)?.let(::thrown) }

/** Asserts this is at most [bound], and returns it. */
infix fun <T : Comparable<T>> T.shouldBeLessThanOrEqual(bound: T): T = also { notAtMost(bound)?.let(::thrown) }

/** Asserts this is greater than [bound], and returns it. */
infix fun <T : Comparable<T>> T.shouldBeGreaterThan(bound: T): T = also { notGreaterThan(bound)?.let(::thrown) }

/** Asserts this is at least [bound], and returns it. */
infix fun <T : Comparable<T>> T.shouldBeGreaterThanOrEqual(bound: T): T = also { notAtLeast(bound)?.let(::thrown) }

/** Asserts this lies in [range], its ends included, and returns it. */
infix fun <T : Comparable<T>> T.shouldBeInRange(range: ClosedRange<T>): T = also { notIn(range)?.let(::thrown) }
