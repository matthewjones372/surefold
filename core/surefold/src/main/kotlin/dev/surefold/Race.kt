package dev.surefold

import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.left
import dev.surefold.fail.right
import java.util.concurrent.Executor

/**
 * Runs both branches on forks of their own and answers with the first to return; the losers are interrupted and their
 * outcomes dropped, and a branch that fails first loses the race for everyone.
 */
fun <E, A, B> Fail<E>.raceN(fa: Fail<E>.() -> A, fb: Fail<E>.() -> B): Either<A, B> = raceN(VirtualThreads, fa, fb)

/** The same, with every fork run on [on]. */
fun <E, A, B> Fail<E>.raceN(
    on: Executor,
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
): Either<A, B> {
    val flight = Flight(this, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    flight.settleFirst()
    return if (a.answered()) a.await().left() else b.await().right()
}

/** Three branches, the winner on the side it was given. */
fun <E, A, B, C> Fail<E>.raceN(
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
): Either<A, Either<B, C>> = raceN(VirtualThreads, fa, fb, fc)

/** The same, with every fork run on [on]. */
fun <E, A, B, C> Fail<E>.raceN(
    on: Executor,
    fa: Fail<E>.() -> A,
    fb: Fail<E>.() -> B,
    fc: Fail<E>.() -> C,
): Either<A, Either<B, C>> {
    val flight = Flight(this, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    flight.settleFirst()
    return when {
        a.answered() -> a.await().left()
        b.answered() -> b.await().left().right()
        else -> c.await().right().right()
    }
}

/** The same race for code outside any `Fail`, with plain branches. */
fun <A, B> raceN(fa: () -> A, fb: () -> B): Either<A, B> = raceN(VirtualThreads, fa, fb)

/** The same, with every fork run on [on]. */
fun <A, B> raceN(
    on: Executor,
    fa: () -> A,
    fb: () -> B,
): Either<A, B> = Unraisable.raceN(on, { fa() }, { fb() })

/** Three branches, the winner on the side it was given. */
fun <A, B, C> raceN(fa: () -> A, fb: () -> B, fc: () -> C): Either<A, Either<B, C>> = raceN(VirtualThreads, fa, fb, fc)

/** The same, with every fork run on [on]. */
fun <A, B, C> raceN(
    on: Executor,
    fa: () -> A,
    fb: () -> B,
    fc: () -> C,
): Either<A, Either<B, C>> = Unraisable.raceN(on, { fa() }, { fb() }, { fc() })
