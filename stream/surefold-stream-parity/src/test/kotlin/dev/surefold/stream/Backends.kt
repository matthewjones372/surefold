package dev.surefold.stream

import dev.surefold.Resource
import dev.surefold.resource
import org.apache.pekko.actor.ActorSystem

/** An actor system named [name], for a suite's `shared`: started as the suite begins, stopped after its last test. */
internal fun actorSystem(name: String): Resource<ActorSystem> = resource {
    install({ ActorSystem.create(name) }) { system, _ ->
        system.terminate()
        system.getWhenTerminated().toCompletableFuture().join()
    }
}

/**
 * A backend a suite runs a test on: named, so a test can be registered for it before the suite's actor system
 * exists, and made fresh for each test from that system, so no two tests running at once share one.
 */
internal class Backend(val name: String, private val make: (ActorSystem) -> StreamBackend) {

    fun on(system: ActorSystem): StreamBackend =
        make(system).also { check(it.key.name == name) { "$name is named ${it.key.name} by its key" } }
}

internal val OnPekko = Backend("Pekko") { PekkoStreams(it) }
internal val OnForks = Backend("Forks") { Forks() }
internal val OnTestStreams = Backend("TestStreams") { TestStreams() }
internal val OnActors = Backend("Actors") { Actors(heldScope) }
