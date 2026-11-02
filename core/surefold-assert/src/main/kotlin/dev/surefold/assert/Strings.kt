@file:OptIn(FailureSeam::class)

package dev.surefold.assert

/** Asserts this string contains [part], and returns it. */
infix fun String?.shouldContain(part: String): String = checked(missing(part))

/** Asserts this string does not contain [part], and returns it. */
infix fun String?.shouldNotContain(part: String): String = checked(holding(part))

/** Asserts this string starts with [prefix], and returns it. */
infix fun String?.shouldStartWith(prefix: String): String = checked(notStartingWith(prefix))

/** Asserts this string ends with [suffix], and returns it. */
infix fun String?.shouldEndWith(suffix: String): String = checked(notEndingWith(suffix))

/** Asserts this string has something in it besides whitespace, and returns it. */
fun String?.shouldNotBeBlank(): String = checked(blank())

/** Asserts the whole of this string matches [pattern], and returns it. */
infix fun String?.shouldMatch(pattern: String): String = shouldMatch(Regex(pattern))

/** The same, for a pattern already compiled. */
infix fun String?.shouldMatch(pattern: Regex): String = checked(notMatching(pattern))

/** Asserts some part of this string matches [pattern], and returns it. */
infix fun String?.shouldContain(pattern: Regex): String = checked(noMatchFor(pattern))

// Every string check misses on null, so a string that passed is not null.
private fun String?.checked(miss: Miss?): String = miss?.let(::thrown) ?: checkNotNull(this)
