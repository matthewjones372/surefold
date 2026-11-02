package dev.surefold.actor.pekko

import dev.surefold.assert.shouldBe
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.javadsl.Adapter
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

private sealed interface Tally

private data class Add(val n: Int) : Tally

private data class Total(val replyTo: ActorRef<Int>) : Tally

private data class Close(val closed: CompletableFuture<Unit>) : Tally

private fun tally(total: Int): Behavior<Tally> = receive { context, message ->
    when (message) {
        is Add -> tally(total + message.n)

        is Total -> message.replyTo.answer(total)

        is Close -> {
            context.self.path()
            message.closed.complete(Unit)
            stopped()
        }
    }
}

/** Spec 0058: an actor written in receive, same, stopped and answer behaves as Pekko's own. */
class ReceiveTest {

    companion object {
        @JvmStatic
        val system: ActorSystem = ActorSystem.create("surefold-actor-pekko-receive-test")

        @JvmStatic
        @AfterAll
        fun stop() {
            system.terminate()
        }
    }

    @Test
    fun `messages fold into the next behaviour, an answer keeps it, and stopped ends the actor`() {
        val ref = Adapter.spawn(system, tally(0), "tally")
        ref.tell(Add(2))
        ref.tell(Add(3))

        var total = 0
        Thread.ofVirtual().start {
            total = ref.ask(system, 3.seconds) { replyTo: ActorRef<Int> -> Total(replyTo) }
        }.join()
        total shouldBe 5

        val closed = CompletableFuture<Unit>()
        ref.tell(Close(closed))
        closed.get(3, TimeUnit.SECONDS) shouldBe Unit
    }
}
