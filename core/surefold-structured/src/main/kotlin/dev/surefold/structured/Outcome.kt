package dev.surefold.structured

import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import dev.surefold.fail.fold
import java.util.concurrent.StructuredTaskScope

internal sealed interface Outcome<out E, out T>

internal class Returned<T>(val value: T) : Outcome<Nothing, T>

internal sealed interface Failure<out E> : Outcome<E, Nothing>

internal class Raised<E>(val error: E) : Failure<E>

internal class Thrown(val throwable: Throwable) : Failure<Nothing>

/**
 * A branch runs under a `Fail` of its own, since a `Fail` never crosses a thread, and whatever it does is kept as a
 * value: a throw left to end the subtask would reach the JDK as a failure nobody asked for.
 */
internal fun <E, T> capture(body: Fail<E>.() -> T): Outcome<E, T> =
    try {
        attempt { body() }.fold({ Raised(it) }, { Returned(it) })
    } catch (t: Throwable) {
        Thrown(t)
    }

internal fun <E> Fail<E>.surface(failure: Failure<E>): Nothing =
    when (failure) {
        is Raised -> fail(failure.error)
        is Thrown -> throw failure.throwable
    }

/** A joined branch's value, or its failure surfaced on the caller's thread. */
internal fun <E, T> Fail<E>.valueOf(subtask: StructuredTaskScope.Subtask<Outcome<E, T>>): T =
    when (val outcome = subtask.get()) {
        is Returned -> outcome.value
        is Failure -> surface(outcome)
    }
