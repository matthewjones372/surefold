package dev.surefold.actor.pekko

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.javadsl.ActorContext
import org.apache.pekko.actor.typed.javadsl.Behaviors

/**
 * A behaviour that answers each message with the next one (spec 0058): Pekko's `Behaviors.receiveMessage`, written
 * so a `when` over a sealed protocol is the whole body, and a message the `when` forgot is a compile error.
 */
fun <T : Any> receive(onMessage: (message: T) -> Behavior<T>): Behavior<T> =
    Behaviors.receiveMessage { message -> onMessage(message) }

/** [receive] with the actor's context, for spawning, watching or [pipeToSelf]: `Behaviors.setup` around it. */
fun <T : Any> receive(onMessage: (context: ActorContext<T>, message: T) -> Behavior<T>): Behavior<T> =
    Behaviors.setup { context -> Behaviors.receiveMessage { message -> onMessage(context, message) } }

/** The behaviour as it is: Pekko's `Behaviors.same()`, without the prefix. */
fun <T : Any> same(): Behavior<T> = Behaviors.same()

/** The actor stops: Pekko's `Behaviors.stopped()`, without the prefix. */
fun <T : Any> stopped(): Behavior<T> = Behaviors.stopped()

/**
 * [reply] sent here, and the asking actor's behaviour left as it is. The reply is bound to `Any` for `ask`'s reason:
 * Pekko refuses a null message, so an answer of "nothing" has to be a value, not a null.
 */
fun <A : Any, T : Any> ActorRef<A>.answer(reply: A): Behavior<T> {
    tell(reply)
    return Behaviors.same()
}
