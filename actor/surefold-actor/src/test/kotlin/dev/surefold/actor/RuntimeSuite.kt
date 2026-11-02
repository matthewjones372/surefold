package dev.surefold.actor

import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeRight
import dev.surefold.junit.shouldContainExactlyInAnyOrder
import dev.surefold.junit.shouldNotBe
import dev.surefold.junit.withClue
import dev.surefold.scope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private sealed interface Log

private data class Add(val n: Int) : Log

private data class Read(val reply: Reply<List<Int>>) : Log

private data class Where(val reply: Reply<Thread>) : Log

private data class Ignore(val reply: Reply<Int>) : Log

private data class Quit(val reply: Reply<Int>) : Log

private data class Wait(val until: CountDownLatch) : Log

private data class Forward(val to: ActorRef<Log>, val message: Log) : Log

private data object Break : Log

private fun log(): Behaviour<Log, List<Int>, Nothing> = behaviour(emptyList()) { _, seen, message ->
    when (message) {
        is Add -> become(seen + message.n)

        is Read -> {
            message.reply(seen)
            stay()
        }

        is Where -> {
            message.reply(Thread.currentThread())
            stay()
        }

        is Ignore -> stay()

        is Quit -> stop()

        is Wait -> {
            message.until.await()
            stay()
        }

        is Forward -> {
            message.to.tell(message.message)
            stay()
        }

        Break -> error("broken")
    }
}

object RuntimeSuite : Suite() {
    init {
        test("a step runs on a virtual thread") {
            val thread = scope {
                spawn("log", log()).ask(1.minutes) { Where(it) }.getOrNull()!!
            }

            thread.isVirtual shouldBe true
        }

        test("one sender's messages arrive in the order they were told") {
            val seen = scope {
                val log = spawn("log", log(), capacity = 16)
                (1..1_000).forEach { log.tell(Add(it)) }
                log.ask(1.minutes) { Read(it) }
            }

            seen shouldBeRight (1..1_000).toList()
        }

        test("an idle actor holds no thread") {
            scope {
                val log = spawn("log", log())
                val first = log.ask(1.minutes) { Where(it) }.getOrNull()!!

                first.join()
                withClue("the thread that handled the first message ended once the mailbox was empty") {
                    first.isAlive shouldBe false
                }
                log.ask(1.minutes) { Where(it) }.getOrNull() shouldNotBe first
            }
        }

        test("closing the scope stops every actor it spawned") {
            val escaped = AtomicReference<ActorRef<Log>>()
            val _ = scope {
                escaped.set(spawn("log", log()))
                escaped.get().tell(Add(1))
            }

            escaped.get().ask(1.minutes) { Read(it) } shouldBe AskFailure.Stopped.left()
        }

        test("an ask nobody answers times out") {
            val answer = scope { spawn("log", log()).ask(50.milliseconds) { Ignore(it) } }

            answer shouldBe AskFailure.TimedOut.left()
        }

        test("an ask the actor stops on answers Stopped, without waiting out its time") {
            val answer = scope { spawn("log", log()).ask(10.minutes) { Quit(it) } }

            answer shouldBe AskFailure.Stopped.left()
        }

        test("a throw stops the actor") {
            val answer = scope {
                val log = spawn("log", log())
                log.tell(Break)
                log.ask(10.minutes) { Read(it) }
            }

            answer shouldBe AskFailure.Stopped.left()
        }

        test("a tell into a full mailbox fails an actor, and waits for anyone else") {
            val answers = scope {
                val release = CountDownLatch(1)
                val full = spawn("full", log(), capacity = 1)
                val sender = spawn("sender", log())
                full.tell(Wait(release))
                // The first is taken off the mailbox and is being handled; this one fills it.
                full.tell(Add(1))

                sender.tell(Forward(full, Add(2)))
                val senderAfter = sender.ask(10.minutes) { Read(it) }

                val outside = Thread.ofVirtual().start { full.tell(Add(3)) }
                release.countDown()
                outside.join()
                senderAfter to full.ask(10.minutes) { Read(it) }
            }

            answers shouldBe (AskFailure.Stopped.left() to listOf(1, 3).right())
        }

        test(
            "a sender waiting on a full mailbox is let go when the actor stops, and its message is a dead letter",
        ) {
            val letters = java.util.concurrent.ConcurrentLinkedQueue<Any>()
            val letGo = scope {
                onDeadLetter { letters += it.message }
                val release = CountDownLatch(1)
                val full = spawn("full", log(), capacity = 1)
                full.tell(Wait(release))
                full.tell(Add(1))

                val waiting = Thread.ofVirtual().start { full.tell(Add(2)) }
                stop(full)
                release.countDown()
                watch(full).await()
                waiting.join(java.time.Duration.ofMinutes(1))
            }

            letGo shouldBe true
            letters.toList() shouldContainExactlyInAnyOrder listOf(Add(1), Add(2))
        }
    }
}
