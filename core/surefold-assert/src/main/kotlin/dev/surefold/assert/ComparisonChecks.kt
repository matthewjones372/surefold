package dev.surefold.assert

// The checks behind the comparison matchers: each answers what went wrong, or null when nothing did.

/** This is not less than [bound]. */
@FailureSeam
fun <T : Comparable<T>> T.notLessThan(bound: T): Miss? = compared(this < bound, "less than $bound")

/** This is more than [bound]. */
@FailureSeam
fun <T : Comparable<T>> T.notAtMost(bound: T): Miss? = compared(this <= bound, "at most $bound")

/** This is not greater than [bound]. */
@FailureSeam
fun <T : Comparable<T>> T.notGreaterThan(bound: T): Miss? = compared(this > bound, "greater than $bound")

/** This is less than [bound]. */
@FailureSeam
fun <T : Comparable<T>> T.notAtLeast(bound: T): Miss? = compared(this >= bound, "at least $bound")

/** This lies outside [range]. */
@FailureSeam
fun <T : Comparable<T>> T.notIn(range: ClosedRange<T>): Miss? = compared(this in range, "in $range")

@OptIn(FailureSeam::class)
private fun Any?.compared(holds: Boolean, wanted: String): Miss? = if (holds) null else Miss(null, this, wanted)
