package io.github.matthewjones372.surefold.app.pekko

import dev.surefold.await
import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.Wiring
import io.github.matthewjones372.surefold.app.single
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.javadsl.Adapter
import org.apache.pekko.pattern.Patterns
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import java.time.Duration as JavaDuration

/**
 * Spawns [behavior] and stops it when the scope ends. The stop is awaited, so an actor holding a
 * connection has given it back before the node that opened the connection is released.
 */
fun <T : Any> Wiring.spawn(
    system: ActorSystem,
    name: String,
    behavior: Behavior<T>,
    stopWithin: Duration,
): ActorRef<T> = install({ Adapter.spawn(system, behavior, name) }) { ref, _ ->
    Patterns.gracefulStop(Adapter.toClassic(ref), JavaDuration.ofMillis(stopWithin.inWholeMilliseconds)).await()
}

/**
 * An actor as a node, keyed by the `ActorRef<T>` of its protocol: two actors are two keys wherever
 * they answer to two protocols, and a node depending on one names the protocol it sends.
 */
inline fun <reified T : Any> actor(
    name: String,
    stopWithin: Duration = 5.seconds,
    noinline behavior: () -> Behavior<T>,
): Module = single { system: ActorSystem -> spawn(system, name, behavior(), stopWithin) }

inline fun <reified T : Any, reified D1 : Any> actor(
    name: String,
    stopWithin: Duration = 5.seconds,
    noinline behavior: (D1) -> Behavior<T>,
): Module = single { system: ActorSystem, d1: D1 -> spawn(system, name, behavior(d1), stopWithin) }

inline fun <reified T : Any, reified D1 : Any, reified D2 : Any> actor(
    name: String,
    stopWithin: Duration = 5.seconds,
    noinline behavior: (D1, D2) -> Behavior<T>,
): Module = single { system: ActorSystem, d1: D1, d2: D2 -> spawn(system, name, behavior(d1, d2), stopWithin) }
