package io.github.matthewjones372.surefold.stream

import com.typesafe.config.Config
import dev.surefold.Resource
import dev.surefold.resource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem as TypedActorSystem

/**
 * An actor system for one suite, as a surefold-junit suite's shared resource (spec 0191).
 *
 * Pekko's own testkit owns it, and the suite's release shuts it down: a system started by hand is one a failing
 * test can leave running, and the threads it holds outlive the suite that leaked them.
 */
class PekkoSystem internal constructor(internal val testKit: ActorTestKit) {

    /** What `run` takes. */
    val system: ClassicActorSystemProvider get() = testKit.system()

    /** What Pekko's own testkit sinks ask for. */
    val classic: ActorSystem get() = testKit.system().classicSystem()

    /** What LoggingTestKit asks for; the same system, seen from the side it was created on. */
    val typed: TypedActorSystem<*> get() = testKit.system()
}

/** A [PekkoSystem] named [name], started when the suite acquires it. [config] is for a system that must differ. */
fun pekkoSystem(name: String, config: Config? = null): Resource<PekkoSystem> = resource {
    install({ PekkoSystem(config?.let { ActorTestKit.create(name, it) } ?: ActorTestKit.create(name)) }) { pekko, _ ->
        pekko.testKit.shutdownTestKit()
    }
}
