package dev.surefold.arrow

import arrow.core.raise.Raise
import arrow.core.raise.either
import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.NonEmptyList
import dev.surefold.fail.attempt
import dev.surefold.fail.fail
import dev.surefold.fail.fold
import dev.surefold.fail.nonEmptyListOf

fun <E, A> Either<E, A>.toArrow(): arrow.core.Either<E, A> =
    fold({ arrow.core.Either.Left(it) }, { arrow.core.Either.Right(it) })

fun <E, A> arrow.core.Either<E, A>.toSurefold(): Either<E, A> = fold({ Either.Left(it) }, { Either.Right(it) })

// Both lists are non-empty by construction, so neither conversion has a case to answer.
fun <A> NonEmptyList<A>.toArrow(): arrow.core.NonEmptyList<A> = arrow.core.NonEmptyList(head, tail)

fun <A> arrow.core.NonEmptyList<A>.toSurefold(): NonEmptyList<A> = nonEmptyListOf(head) + tail

/** Runs Surefold code inside an Arrow `Raise`: a [fail] in [block] raises its error here. */
fun <E, A> Raise<E>.surefold(block: Fail<E>.() -> A): A = attempt(block).fold({ raise(it) }, { it })

/** Runs Arrow code inside a Surefold [Fail]: a raise in [block] fails with its error here. */
context(_: Fail<E>)
fun <E, A> arrow(block: Raise<E>.() -> A): A = either(block).fold({ fail(it) }, { it })
