package dev.surefold.assert

// The checks behind the string matchers: each answers what went wrong, or null when nothing did. A null string misses.

/** This string does not contain [part]. */
@FailureSeam fun String?.missing(part: String): Miss? = checked("a string containing ${quoted(part)}") { part in it }

/** This string contains [part]. */
@FailureSeam fun String?.holding(part: String): Miss? = checked("a string without ${quoted(part)}") { part !in it }

/** This string does not start with [prefix]. */
@FailureSeam
fun String?.notStartingWith(prefix: String): Miss? =
    checked("a string starting with ${quoted(prefix)}") { it.startsWith(prefix) }

/** This string does not end with [suffix]. */
@FailureSeam
fun String?.notEndingWith(suffix: String): Miss? =
    checked("a string ending with ${quoted(suffix)}") { it.endsWith(suffix) }

/** This string has nothing in it but whitespace. */
@FailureSeam fun String?.blank(): Miss? = checked("a string that is not blank") { it.isNotBlank() }

/** The whole of this string does not match [pattern]. */
@FailureSeam
fun String?.notMatching(pattern: Regex): Miss? =
    checked("a string matching /${pattern.pattern}/") { pattern.matches(it) }

/** No part of this string matches [pattern]. */
@FailureSeam
fun String?.noMatchFor(pattern: Regex): Miss? =
    checked("a string containing a match for /${pattern.pattern}/") { pattern.containsMatchIn(it) }

@OptIn(FailureSeam::class)
private inline fun String?.checked(wanted: String, holds: (String) -> Boolean): Miss? =
    if (this != null && holds(this)) null else Miss(null, this?.let(::quoted), wanted)

private fun quoted(text: String): String = "\"$text\""
