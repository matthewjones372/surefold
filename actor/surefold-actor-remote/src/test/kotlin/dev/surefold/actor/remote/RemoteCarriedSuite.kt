package dev.surefold.actor.remote

import dev.surefold.actor.Address
import dev.surefold.actor.Reply
import dev.surefold.actor.ask
import dev.surefold.actor.behaviour
import dev.surefold.actor.spawn
import dev.surefold.actor.stay
import dev.surefold.capturingLogs
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.logAnnotated
import dev.surefold.logInfo
import dev.surefold.scope
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

/** Asks for the request id on a line the handler writes. */
private data class WhoAsked(val reply: Reply<String>)

private val whoAskedCodec = object : MessageCodec<WhoAsked> {
    override fun write(message: WhoAsked, out: WireOut) = out.reply(message.reply, Codecs.string)

    override fun read(input: WireIn) = WhoAsked(input.reply(Codecs.string))
}

private fun answering() = behaviour<WhoAsked, Unit>(Unit) { _, _, message ->
    val heard = capturingLogs { logs ->
        logInfo("asked")
        logs.all().single().annotations["request_id"].orEmpty()
    }
    stay().also { message.reply(heard) }
}

/** Spec 0122 across nodes: what an asker had bound is what the handler on the other node runs with. */
object RemoteCarriedSuite : Suite() {
    init {
        test("an ask made inside logAnnotated is handled on another node with the asker's annotations") {
            val port = freePort()
            val ready = CountDownLatch(1)
            val done = CountDownLatch(1)
            val other = Thread.ofPlatform().start {
                scope {
                    val node = node("answering", port)
                    node.expose(spawn("answer", answering()), whoAskedCodec)
                    ready.countDown()
                    done.await()
                }
            }
            ready.await()
            try {
                val heard = scope {
                    val asking = node("asking", freePort())
                    val answer = asking.remote(Address("answering@127.0.0.1:$port", "/user/answer", 0), whoAskedCodec)
                    logAnnotated("request_id" to "r-9") { answer.ask(1.minutes) { WhoAsked(it) }.getOrNull()!! }
                }

                heard shouldBe "r-9"
            } finally {
                done.countDown()
                other.join()
            }
        }
    }
}

/** Asks the other node to answer [to], a reply that tells an actor here rather than an ask's caller. */
private data class AnswerTo(val to: Reply<String>)

private val answerToCodec = object : MessageCodec<AnswerTo> {
    override fun write(message: AnswerTo, out: WireOut) = out.reply(message.to, Codecs.string)

    override fun read(input: WireIn) = AnswerTo(input.reply(Codecs.string))
}

private fun answeringTo() = behaviour<AnswerTo, Unit>(Unit) { _, _, message -> stay().also { message.to("answered") } }

/** A reply that tells [heard] the annotations on a line written as it is answered, as a saga's leg reply does. */
private class Telling(private val heard: CompletableFuture<String>) : Reply<String> {
    override val address = Address("here", "/telling", 0)

    override fun invoke(answer: String) {
        heard.complete(
            capturingLogs { logs ->
                logInfo(answer)
                logs.all().single().annotations["request_id"].orEmpty()
            },
        )
    }
}

object RemoteReplyCarriedSuite : Suite() {
    init {
        test("an answer to a reply crosses back inside the trace it was given in") {
            val port = freePort()
            val ready = CountDownLatch(1)
            val done = CountDownLatch(1)
            val other = Thread.ofPlatform().start {
                scope {
                    node("answering", port).expose(spawn("answer", answeringTo()), answerToCodec)
                    ready.countDown()
                    done.await()
                }
            }
            ready.await()
            try {
                val heard = CompletableFuture<String>()
                scope {
                    val asking = node("asking", freePort())
                    val answer = asking.remote(Address("answering@127.0.0.1:$port", "/user/answer", 0), answerToCodec)
                    logAnnotated("request_id" to "r-10") { answer.tell(AnswerTo(Telling(heard))) }
                    heard.get(1, TimeUnit.MINUTES)
                }

                heard.get() shouldBe "r-10"
            } finally {
                done.countDown()
                other.join()
            }
        }
    }
}
