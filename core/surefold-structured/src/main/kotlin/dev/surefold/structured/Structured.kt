package dev.surefold.structured

import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.left
import dev.surefold.fail.right
import java.util.concurrent.StructuredTaskScope
import java.util.concurrent.TimeoutException
import kotlin.time.Duration

// surefold's own combinators, with the same names and shapes, each running its branches as subtasks of a JDK
// StructuredTaskScope opened for that one call. Importing these instead of surefold's is the whole switch.

/** Runs both branches at once and combines their values; the first to fail cancels the other and is the answer. */
fun <E, A, B, C> Fail<E>.parZip(fa: Fail<E>.() -> A, fb: Fail<E>.() -> B, f: (A, B) -> C): C =
    Tasks<E, Failure<E>?>(FirstFailure()).use { tasks ->
        val a = tasks.fork(fa)
        val b = tasks.fork(fb)
        tasks.join()?.let { surface(it) }
        f(valueOf(a), valueOf(b))
    }

/** The same for three branches. */
fun <E, A, B, C, D> Fail<E>.parZip(
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
    f: (A, B, C) -> D,
): D = Tasks<E, Failure<E>?>(FirstFailure()).use { tasks ->
    val a = tasks.fork(fa)
    val b = tasks.fork(fb)
    val c = tasks.fork(fc)
    tasks.join()?.let { surface(it) }
    f(valueOf(a), valueOf(b), valueOf(c))
}

/** Runs [f] over every element at once and answers in the iterable's order; the first to fail is the answer. */
fun <E, A, B> Fail<E>.parMap(iterable: Iterable<A>, f: Fail<E>.(A) -> B): List<B> =
    Tasks<E, Failure<E>?>(FirstFailure()).use { tasks ->
        val subtasks = iterable.map { element -> tasks.fork { f(element) } }
        tasks.join()?.let { surface(it) }
        subtasks.map { valueOf(it) }
    }

/**
 * Runs both branches at once and answers with the first to finish, on the side it was given; the loser is
 * cancelled. A branch that fails first loses the race for everyone.
 */
fun <E, A, B> Fail<E>.raceN(fa: Fail<E>.() -> A, fb: Fail<E>.() -> B): Either<A, B> =
    Tasks<E, StructuredTaskScope.Subtask<*>?>(FirstToFinish()).use { tasks ->
        val a = tasks.fork(fa)
        val b = tasks.fork(fb)
        val winner = checkNotNull(tasks.join()) { "a joined race has a winner" }
        if (winner === a) valueOf(a).left() else valueOf(b).right()
    }

/** Runs [block] with a deadline of [duration]: past it, the block is cancelled and [onTimeout] is the failure. */
fun <E, A> Fail<E>.timeout(duration: Duration, onTimeout: () -> E, block: Fail<E>.() -> A): A =
    withDeadline(duration, block) { fail(onTimeout()) }

/** The same, throwing `TimeoutException` past the deadline, as surefold's `timeout` does. */
fun <E, A> Fail<E>.timeout(duration: Duration, block: Fail<E>.() -> A): A =
    withDeadline(duration, block) { throw TimeoutException("no answer within $duration") }

/** The same, answering null past the deadline. */
fun <E, A> Fail<E>.timeoutOrNull(duration: Duration, block: Fail<E>.() -> A): A? =
    Tasks<E, Boolean>(Expiry(), duration).use { tasks ->
        val answer = tasks.fork(block)
        if (tasks.join() && !answer.hasAnswered()) null else valueOf(answer)
    }

private inline fun <E, A> Fail<E>.withDeadline(
    duration: Duration,
    noinline block: Fail<E>.() -> A,
    expired: () -> Nothing,
): A = Tasks<E, Boolean>(Expiry(), duration).use { tasks ->
    val answer = tasks.fork(block)
    // An answer that got in before the deadline is still the answer.
    if (tasks.join() && !answer.hasAnswered()) expired() else valueOf(answer)
}
