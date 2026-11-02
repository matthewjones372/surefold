package io.github.matthewjones372.surefold.actor.pekko

import dev.surefold.await
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.javadsl.Adapter
import org.apache.pekko.actor.typed.javadsl.AskPattern
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Asks [message] of this actor and waits for the answer on the calling virtual thread.
 *
 * The reply is bound to `Any`, which is the point. Pekko refuses a null message, so an actor
 * answering "there is no such thing" with `null` throws where it meant to answer — and an
 * `ActorRef<Pet?>` compiles, so neither Kotlin nor a typed protocol stops you writing it. The bound
 * does: absence has to be modelled, as an `Option`, a sealed reply, or an empty list.
 */
fun <Command, Reply : Any> ActorRef<Command>.ask(
    system: ClassicActorSystemProvider,
    within: Duration,
    message: (ActorRef<Reply>) -> Command,
): Reply =
    AskPattern.ask(this, message, within.toJavaDuration(), Adapter.toTyped(system.classicSystem()).scheduler())
        .await()
