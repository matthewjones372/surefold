package io.github.matthewjones372.surefold.bank

import dev.surefold.actor.Behaviour
import dev.surefold.actor.Delivered

/**
 * [behaviour] given the command inside each one sent reliably, and confirming it once the turn returns.
 *
 * `delivered` over a persistent behaviour drops a resent command without a turn, so what that turn would have sent on
 * is lost if the node crashed after the events were written and before it was sent. Here the entity sees every copy
 * and answers a repeat from its state, by the transfer's id, sending on again whatever the first copy sent.
 */
internal fun <M : Any, S, E> repeatable(behaviour: Behaviour<M, S, E>, unwrap: (M) -> M): Behaviour<M, S, E> =
    Behaviour(
        initial = behaviour.initial,
        turn = { ctx, state, message ->
            behaviour.turn(this, ctx, state, unwrap(message)).also { (message as? Delivered)?.delivery?.confirm() }
        },
        signal = behaviour.signal,
        start = behaviour.start,
    )
