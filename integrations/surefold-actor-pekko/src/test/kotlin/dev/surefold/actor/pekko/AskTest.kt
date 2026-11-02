package dev.surefold.actor.pekko

import dev.surefold.assert.shouldBe
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.javadsl.Adapter
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

private data class Echo(val said: String, val replyTo: ActorRef<String>)

private fun echo(): Behavior<Echo> = Behaviors.receive(Echo::class.java)
    .onMessage(Echo::class.java) { asked ->
        asked.replyTo.tell(asked.said)
        Behaviors.same()
    }
    .build()

/** `ask` on its own, with no wiring graph on the classpath (spec 0058). */
class AskTest {

    companion object {
        @JvmStatic
        val system: ActorSystem = ActorSystem.create("surefold-actor-pekko-ask-test")

        @JvmStatic
        @AfterAll
        fun stop() {
            system.terminate()
        }
    }

    @Test
    fun `an answer comes back on the calling virtual thread`() {
        val ref = Adapter.spawn(system, echo(), "echo")

        var answer = "" to false
        Thread.ofVirtual().start {
            val heard = ref.ask(system, 3.seconds) { replyTo: ActorRef<String> -> Echo("anvil", replyTo) }
            answer = heard to Thread.currentThread().isVirtual
        }.join()

        answer shouldBe ("anvil" to true)
    }
}
