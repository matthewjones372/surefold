package dev.surefold.replay

import dev.surefold.TurnSeam
import dev.surefold.fail.Either
import dev.surefold.fail.fold
import dev.surefold.takingTurns
import java.util.SplittableRandom

/** Where [explore]'s orders start when it is not given a seed: the same for every run of the same test. */
private const val FIRST_ORDERS = 0x0042L

/**
 * Runs [body] [runs] times, its forks in a different order each time, chosen from [seed] (spec 0042). An order that
 * makes [body] throw fails the test with an [AssertionError] naming it, and [replay] runs exactly that order again.
 *
 * Forks run on their own threads, but take turns: one runs between the places a fork waits through surefold
 * (`await`, a fork starting or ending, `cancel`, a channel, `select`). A body that waits anywhere else holds the turn.
 */
fun explore(runs: Int = 1_000, seed: Long = FIRST_ORDERS, body: () -> Unit) {
    require(runs > 0) { "explore needs a run, and was asked for $runs" }
    val orders = SplittableRandom(seed)
    repeat(runs) { run ->
        val order = orders.nextInt().toLong() and ORDER_BITS
        inOrder(order, body)?.let { broke ->
            throw AssertionError(
                "order ${hex(order)} broke it on run ${run + 1}; replay(${hex(order)}) { … } runs exactly that order",
                broke,
            )
        }
    }
}

/** Runs [body] once, its forks in the [order] an [explore] failure named, and throws what it throws. */
fun replay(order: Long, body: () -> Unit) {
    inOrder(order, body)?.let { throw it }
}

/** What [body] threw with its forks in [order], or null if it returned. */
@OptIn(TurnSeam::class)
private fun inOrder(order: Long, body: () -> Unit): Throwable? {
    val turns = Order(order)
    return takingTurns(turns) {
        turns.enter()
        val thrown = try {
            // Anything fatal, an interrupt among them, goes on up rather than being named as an order's failure.
            Either.catch(body).fold({ it }, { null })
        } finally {
            turns.leave()
        }
        // A run that left determinism cannot promise its order replays, whatever it ended with (spec 0042).
        turns.stuck?.let { AssertionError("the run left determinism: $it", thrown) } ?: thrown
    }
}

/** An order is 32 bits, short enough to read off a failure and write into a test. */
private const val ORDER_BITS = 0xffff_ffffL

private fun hex(order: Long): String = "0x" + java.lang.Long.toHexString(order)
