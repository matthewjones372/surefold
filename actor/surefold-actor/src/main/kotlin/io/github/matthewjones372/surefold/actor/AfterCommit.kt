package io.github.matthewjones372.surefold.actor

import dev.surefold.Transaction

/**
 * Tells [message] to [to] once the step has committed, and not at all if it does not (spec 0211): a `tell` written in
 * the step itself would go out once per run.
 */
fun <M : Any> Transaction.tellAfterCommit(to: ActorRef<M>, message: M) = afterCommit { to.tell(message) }
