package dev.surefold.actor

import dev.surefold.capturingLogs
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldNotContainKey
import dev.surefold.logAnnotated
import dev.surefold.logInfo
import dev.surefold.scope
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

private sealed interface Listened

/** Answers with the annotations on a line written while handling it. */
private data class AnnotationsAsked(val reply: Reply<Map<String, String>>) : Listened

/** Kept until [LetThrough], then handled as an [AnnotationsAsked] would be. */
private data class KeptUntilLetThrough(val reply: Reply<Map<String, String>>) : Listened

private data object LetThrough : Listened

/** The annotations a line written here carries: what the message brought, if anything did. */
private fun annotationsHere(): Map<String, String> = capturingLogs { logs ->
    logInfo("handling")
    logs.all().single().annotations
}

private fun listener() =
    behaviour<Listened, Boolean>(false) { ctx, released, message ->
        when (message) {
            is AnnotationsAsked -> stay().also { message.reply(annotationsHere()) }

            is KeptUntilLetThrough ->
                if (released)
                    stay().also {
                        message.reply(annotationsHere())
                    }
                else stay().also { ctx.stash(message) }

            LetThrough -> {
                ctx.unstashAll()
                become(true)
            }
        }
    }

/** Spec 0122: what a sender had bound rides its message to the handler. */
object CarriedSuite : Suite() {
    init {
        test("an ask made inside logAnnotated is handled with the asker's annotations") {
            val heard = scope {
                val listener = spawn("listener", listener())
                logAnnotated("request_id" to "r-1") { listener.ask(1.minutes) { AnnotationsAsked(it) }.getOrNull()!! }
            }

            heard shouldContain ("request_id" to "r-1")
        }

        test("a message told with nothing bound is handled with nothing") {
            val heard = scope {
                spawn("listener", listener()).ask(1.minutes) { AnnotationsAsked(it) }.getOrNull()!!
            }

            heard shouldNotContainKey "request_id"
        }

        test("a stashed message is replayed with what it carried, not with what released it") {
            val answered = CompletableFuture<Map<String, String>>()
            val reply =
                object : Reply<Map<String, String>> {
                    override val address = Address("test", "/reply", 0)

                    override fun invoke(answer: Map<String, String>) {
                        answered.complete(answer)
                    }
                }
            scope {
                val listener = spawn("listener", listener())
                // Told from one thread, so in this order: kept, then released by a message that carries nothing.
                logAnnotated("request_id" to "r-2") { listener.tell(KeptUntilLetThrough(reply)) }
                listener.tell(LetThrough)
                answered.get(1, TimeUnit.MINUTES)
            }

            answered.get() shouldContain ("request_id" to "r-2")
        }

        test("a burst kept for a busy entity is handled with what each message carried, not with the drain's") {
            val heard = ConcurrentHashMap<Int, String>()
            val open = CountDownLatch(1)
            scope {
                // The first message holds the entity's step until the burst is sent, so its manager keeps most of it.
                val held =
                    behaviour<Int, Unit>(Unit) { _, _, n ->
                        stay().also {
                            if (n == 0) open.await() else heard[n] = annotationsHere()["request_id"].orEmpty()
                        }
                    }
                val rex = spawn("kennel", entities(passivateAfter = 10.minutes) { _ -> held }).entity("rex")
                rex.tell(0)
                (1..5_000).forEach { n -> logAnnotated("request_id" to "r-$n") { rex.tell(n) } }
                open.countDown()
                awaitIdle()
            }

            heard.size shouldBe 5_000
            heard.filter { (n, id) -> id != "r-$n" } shouldBe emptyMap()
        }
    }
}
