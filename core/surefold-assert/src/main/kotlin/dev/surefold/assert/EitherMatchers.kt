@file:OptIn(FailureSeam::class)

package dev.surefold.assert

import dev.surefold.fail.Either

/** Asserts this is a [Either.Right] and returns its value. */
fun <E, A> Either<E, A>.shouldBeRight(): A =
    when (this) {
        is Either.Right -> value
        is Either.Left -> thrown(Miss(null, this, "a Right"))
    }

/** Asserts this is a [Either.Right] holding [expected], and returns it. */
infix fun <E, A> Either<E, A>.shouldBeRight(expected: A): A {
    notEqual(Either.Right(expected))?.let(::thrown)
    return expected
}

/** Asserts this is a [Either.Left] and returns its error. */
fun <E, A> Either<E, A>.shouldBeLeft(): E =
    when (this) {
        is Either.Left -> value
        is Either.Right -> thrown(Miss(null, this, "a Left"))
    }

/** Asserts this is a [Either.Left] holding [expected], and returns it. */
infix fun <E, A> Either<E, A>.shouldBeLeft(expected: E): E {
    notEqual(Either.Left(expected))?.let(::thrown)
    return expected
}
