package dev.surefold

import dev.surefold.fail.Either
import kotlin.time.Duration

/**
 * A value handed over once, by whoever has it (spec 0247): one caller completes it with [succeed] or [fail], and every
 * [await] is answered the same. It is a [Shared] holding no answer yet, so a wait parks on a virtual thread, wakes on
 * the commit that completes it, and ends when its scope is cancelled. A latch is a `Promise<Nothing, Unit>`.
 */
class Promise<E, A>(name: String = "promise") {
    private val answer = Shared<Either<E, A>?>(null, name)

    /** Completes this with [value]; false, changing nothing, if it was already complete. */
    fun succeed(value: A): Boolean = complete(Either.Right(value))

    /** Completes this with [error]; false, changing nothing, if it was already complete. */
    fun fail(error: E): Boolean = complete(Either.Left(error))

    /** Parks until this is complete, for as long as the calling scope lives, and answers how it completed. */
    fun await(): Either<E, A> = transact(answer.name) { answer.get() ?: waitForChange() }

    /** [await], or null if this is not complete within [within] on the clock bound where it is called. */
    fun await(within: Duration): Either<E, A>? = timeoutOrNull(within) { await() }

    /** How this completed, or null while it has not: a look, not a wait. */
    fun peek(): Either<E, A>? = answer.peek()

    // How many threads are parked on this now, for a test that must release a flight only once all have joined it.
    internal fun waiting(): Int = answer.waiters.size

    private fun complete(with: Either<E, A>): Boolean = answer.modify { was -> (was ?: with) to (was == null) }
}

/** [Promise.await] for a promise that cannot fail: its value. */
fun <A> Promise<Nothing, A>.awaitValue(): A = (await() as Either.Right).value

/** [Promise.await] within [within], for a promise that cannot fail: its value, or null once [within] passes. */
fun <A> Promise<Nothing, A>.awaitValue(within: Duration): A? = (await(within) as Either.Right?)?.value
