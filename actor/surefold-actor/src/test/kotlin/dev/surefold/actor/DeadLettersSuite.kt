package dev.surefold.actor

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldContainExactlyInAnyOrder
import dev.surefold.junit.shouldThrow
import dev.surefold.scope
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

private sealed interface Mail

private data class Letter(val n: Int) : Mail

/** Nothing the postbox takes: its step answers `unhandled()`. */
private data object Junk : Mail

/** Kept in the stash, and never put back. */
private data class Hold(val n: Int) : Mail

private data object Seal : Mail

/** Posts two letters to itself, then stops before either is handled. */
private data object Burn : Mail

private fun postbox() = behaviour<Mail, List<Int>>(emptyList()) { ctx, letters, message ->
    when (message) {
        is Letter -> become(letters + message.n)

        Junk -> unhandled()

        is Hold -> {
            ctx.stash(message)
            stay()
        }

        Seal -> stop()

        Burn -> {
            ctx.self.tell(Letter(1))
            ctx.self.tell(Letter(2))
            stop()
        }
    }
}

object DeadLettersSuite : Suite() {
    private const val RACES = 200

    init {
        test("a message told to a stopped actor is a dead letter, once") {
            testActors {
                val postbox = spawn("postbox", postbox())
                postbox.send(Seal)

                postbox.tell(Letter(1))

                deadLetters shouldContainExactly listOf(DeadLetter(postbox.address, Letter(1), DeadLetter.Why.Stopped))
            }
        }

        test("a message the step answers unhandled is a dead letter, and still in unhandled") {
            val postbox = postbox().test()

            postbox.send(Junk)

            postbox.deadLetters shouldContainExactly
                listOf(DeadLetter(postbox.address, Junk, DeadLetter.Why.Unhandled))
            postbox.unhandled shouldContainExactly listOf(Junk)
        }

        test("what is still waiting when an actor stops is a dead letter, in the order it was told") {
            val postbox = postbox().test()

            postbox.send(Burn)

            postbox.deadLetters shouldContainExactly listOf(
                DeadLetter(postbox.address, Letter(1), DeadLetter.Why.Stopped),
                DeadLetter(postbox.address, Letter(2), DeadLetter.Why.Stopped),
            )
        }

        test("a stashed message is a dead letter when the actor stops") {
            val postbox = postbox().test()
            postbox.send(Hold(7))

            postbox.send(Seal)

            postbox.deadLetters shouldContainExactly
                listOf(DeadLetter(postbox.address, Hold(7), DeadLetter.Why.Stopped))
        }

        test("on threads, a scope hands every dead letter to its handler, once") {
            val letters = ConcurrentLinkedQueue<DeadLetter>()
            scope {
                onDeadLetter(letters::add)
                val postbox = spawn("postbox", postbox())
                postbox.tell(Hold(7))
                postbox.tell(Junk)
                postbox.tell(Burn)
                watch(postbox).await()
                postbox.tell(Letter(3))
                postbox.address
            }.let { address ->
                letters.toList() shouldContainExactlyInAnyOrder listOf(
                    DeadLetter(address, Junk, DeadLetter.Why.Unhandled),
                    DeadLetter(address, Hold(7), DeadLetter.Why.Stopped),
                    DeadLetter(address, Letter(1), DeadLetter.Why.Stopped),
                    DeadLetter(address, Letter(2), DeadLetter.Why.Stopped),
                    DeadLetter(address, Letter(3), DeadLetter.Why.Stopped),
                )
                letters.size shouldBe 5
            }
        }

        test("after its scope has closed, a dead letter is only logged, and a spawn is refused") {
            val letters = ConcurrentLinkedQueue<DeadLetter>()
            val closed = scope {
                onDeadLetter(letters::add)
                spawn("postbox", postbox())
                this
            }

            closed.deadLetter(DeadLetter(Address("local", "/user/postbox", 1), Letter(1), DeadLetter.Why.Unreachable))

            letters.toList() shouldBe emptyList()
            shouldThrow<IllegalStateException> { closed.spawn("late", postbox()) }
        }

        test("a dead letter the scope is told of while its actors stop reaches its handler") {
            val letters = ConcurrentLinkedQueue<DeadLetter>()

            scope {
                onDeadLetter(letters::add)
                // As a transport does when its actor stops: what it could not deliver becomes this scope's
                // dead letter.
                val transport = behaviour<Int, Unit>(Unit) { _, _, _ -> stay() }.onSignal { ctx, _, signal ->
                    if (signal is Signal.Stopping) {
                        deadLetter(DeadLetter(ctx.self.address, 1, DeadLetter.Why.Unreachable))
                    }
                    stay()
                }
                spawn("transport", transport)
            }

            letters.map { it.message } shouldContainExactly listOf(1)
        }

        test(
            "a thread the scope did not fork, telling it of dead letters while it closes, stands no second guardian",
        ) {
            val failed = ConcurrentLinkedQueue<Throwable>()
            val letter = DeadLetter(Address("local", "/user/postbox", 1), Letter(1), DeadLetter.Why.Unreachable)

            // A race, so it is run many times: each close meets a transport's thread at a different point.
            repeat(RACES) {
                val done = AtomicBoolean(false)
                val telling = CountDownLatch(1)
                lateinit var transport: Thread
                scope {
                    spawn("postbox", postbox())
                    val closing = this
                    transport = Thread.ofPlatform().start {
                        telling.countDown()
                        try {
                            while (!done.get()) closing.deadLetter(letter)
                        } catch (thrown: IllegalStateException) {
                            failed += thrown
                        }
                    }
                    telling.await()
                }
                done.set(true)
                transport.join()
            }

            failed.toList() shouldBe emptyList()
        }
    }
}
