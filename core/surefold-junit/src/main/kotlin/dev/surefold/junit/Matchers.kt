@file:OptIn(FailureSeam::class)

package dev.surefold.junit

import dev.surefold.assert.FailureSeam
import dev.surefold.assert.Miss
import dev.surefold.assert.blank
import dev.surefold.assert.equal
import dev.surefold.assert.holding
import dev.surefold.assert.missing
import dev.surefold.assert.noMatchFor
import dev.surefold.assert.notAtLeast
import dev.surefold.assert.notAtMost
import dev.surefold.assert.notEndingWith
import dev.surefold.assert.notEqual
import dev.surefold.assert.notGreaterThan
import dev.surefold.assert.notIn
import dev.surefold.assert.notInstanceOf
import dev.surefold.assert.notLessThan
import dev.surefold.assert.notMatching
import dev.surefold.assert.notNull
import dev.surefold.assert.notSameAs
import dev.surefold.assert.notStartingWith
import dev.surefold.assert.notThrown
import dev.surefold.assert.sameAs
import dev.surefold.assert.wasNull
import dev.surefold.fail.Either
import dev.surefold.fail.nonFatalOrThrow

// The matchers: each records the check its surefold-assert namesake throws on, so the two say the same.

/** Holds if this equals [expected]; fails as surefold-assert's `shouldBe` does, carrying both for an IDE's diff. */
infix fun <A> A.shouldBe(expected: A) = verdict(notEqual(expected))

/** Holds if this does not equal [unexpected]. */
infix fun <A> A.shouldNotBe(unexpected: A) = verdict(equal(unexpected))

/** Holds if this is a [Either.Right] holding [expected]. */
infix fun <E, A> Either<E, A>.shouldBeRight(expected: A) = verdict(notEqual(Either.Right(expected)))

/** Holds if this is a [Either.Left] holding [expected]. */
infix fun <E, A> Either<E, A>.shouldBeLeft(expected: E) = verdict(notEqual(Either.Left(expected)))

/** Holds if this is a [Either.Right] and [then], given its value, holds too. [then] runs only on a Right. */
inline fun <E, A> Either<E, A>.shouldBeRight(then: (A) -> Unit) =
    when (this) {
        is Either.Right -> {
            held()
            then(value)
        }

        is Either.Left -> notEither(this, "a Right")
    }

/** Holds if this is a [Either.Left] and [then], given its error, holds too. [then] runs only on a Left. */
inline fun <E, A> Either<E, A>.shouldBeLeft(then: (E) -> Unit) =
    when (this) {
        is Either.Left -> {
            held()
            then(value)
        }

        is Either.Right -> notEither(this, "a Left")
    }

/** Holds if this string contains [part]; fails as surefold-assert's `shouldContain` does, carrying the string. */
infix fun String?.shouldContain(part: String) = verdict(missing(part))

/** Holds if this string does not contain [part]. */
infix fun String?.shouldNotContain(part: String) = verdict(holding(part))

/** Holds if this string starts with [prefix]. */
infix fun String?.shouldStartWith(prefix: String) = verdict(notStartingWith(prefix))

/** Holds if this string ends with [suffix]. */
infix fun String?.shouldEndWith(suffix: String) = verdict(notEndingWith(suffix))

/** Holds if this string has something in it besides whitespace. */
fun String?.shouldNotBeBlank() = verdict(blank())

/** Holds if the whole of this string matches [pattern]. */
infix fun String?.shouldMatch(pattern: String) = shouldMatch(Regex(pattern))

/** Holds if the whole of this string matches [pattern]. */
infix fun String?.shouldMatch(pattern: Regex) = verdict(notMatching(pattern))

/** Holds if some part of this string matches [pattern]. */
infix fun String?.shouldContain(pattern: Regex) = verdict(noMatchFor(pattern))

/** Holds if this is the very object [expected] is, not only an equal one. */
infix fun <A> A.shouldBeSameInstanceAs(expected: A) = verdict(notSameAs(expected))

/** Holds if this is not the very object [unexpected] is, though it may equal it. */
infix fun <A> A.shouldNotBeSameInstanceAs(unexpected: A) = verdict(sameAs(unexpected))

/** Holds if [block] throws a [T]; fails, naming this line, if it throws nothing or something else. */
inline fun <reified T : Throwable> shouldThrow(block: () -> Any?) = shouldThrow<T>(block) { held() }

/**
 * Holds if [block] throws a [T] and [then], given what it threw, holds too. [then] runs only on a [T]. An interrupt, a
 * cancellation or a VM error that is not a [T] goes on up, so the test stops as it would have without the check.
 */
inline fun <reified T : Throwable> shouldThrow(block: () -> Any?, then: (T) -> Unit) {
    @Suppress("TooGenericExceptionCaught") // catching everything is the point; nonFatalOrThrow sorts it
    val thrown =
        try {
            val _ = block()
            null
        } catch (caught: Throwable) {
            caught
        }
    return when (thrown) {
        is T -> {
            held()
            then(thrown)
        }

        else -> verdict(notThrown("a ${T::class.qualifiedName} thrown", thrown?.nonFatalOrThrow()))
    }
}

/**
 * Holds if this is a [T] and [then], given it as one, holds too. [then] runs only on a [T]; a miss names the type
 * wanted and the value found, with its type.
 */
inline fun <reified T> Any?.shouldBeInstanceOf(then: (T) -> Unit) =
    if (this is T) {
        held()
        then(this)
    } else {
        verdict(notInstanceOf<T>())
    }

/** Holds if this is a [T]. */
inline fun <reified T> Any?.shouldBeInstanceOf() = shouldBeInstanceOf<T> { held() }

/** Holds if this is not null and [then], given it, holds too. [then] runs only on a value. */
inline fun <A : Any> A?.shouldNotBeNull(then: (A) -> Unit) =
    if (this != null) {
        held()
        then(this)
    } else {
        verdict(wasNull())
    }

/** Holds if this is not null. */
fun Any?.shouldNotBeNull() = verdict(if (this == null) wasNull() else null)

/** Holds if this is null. */
fun Any?.shouldBeNull() = verdict(notNull())

/** Holds if this is less than [bound]. */
infix fun <T : Comparable<T>> T.shouldBeLessThan(bound: T) = verdict(notLessThan(bound))

/** Holds if this is at most [bound]. */
infix fun <T : Comparable<T>> T.shouldBeLessThanOrEqual(bound: T) = verdict(notAtMost(bound))

/** Holds if this is greater than [bound]. */
infix fun <T : Comparable<T>> T.shouldBeGreaterThan(bound: T) = verdict(notGreaterThan(bound))

/** Holds if this is at least [bound]. */
infix fun <T : Comparable<T>> T.shouldBeGreaterThanOrEqual(bound: T) = verdict(notAtLeast(bound))

/** Holds if this is in [range], its ends included. */
infix fun <T : Comparable<T>> T.shouldBeInRange(range: ClosedRange<T>) = verdict(notIn(range))

/** The check [shouldBeRight] or [shouldBeLeft] found the other side, [either]. */
@PublishedApi
internal fun notEither(either: Either<*, *>, wanted: String) {
    mismatchedHere(null, either, wanted)
}

/** A shared surefold-assert check's [miss] as an expectation at the test's line, or a pass when there is none. */
@PublishedApi
internal fun verdict(miss: Miss?) {
    if (miss == null) held() else mismatchedHere(miss.expected, miss.actual, miss.expectedText, miss.cause)
}
