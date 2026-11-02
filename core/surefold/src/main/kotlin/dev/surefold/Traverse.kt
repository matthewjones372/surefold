package dev.surefold

import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt

/** How many elements [parTraverse] runs at once when it is given no [Limit] (spec 0245). */
const val PAR_TRAVERSE_DEFAULT = 64

/**
 * `traverse` with each element on a fork of its own, at most [limit] at once, answering in the iterable's order: the
 * first to fail interrupts the rest, and none starts after it. With no limit given, each call has its own of
 * [PAR_TRAVERSE_DEFAULT]; pass a dependency's shared one so the traversal counts against its budget.
 */
fun <E, A, B> Iterable<A>.parTraverse(
    limit: Limit = Limit.fixed(PAR_TRAVERSE_DEFAULT, "parTraverse"),
    f: Fail<E>.(A) -> B,
): Either<E, List<B>> = attempt { parMap(this@parTraverse, limit, f) }
