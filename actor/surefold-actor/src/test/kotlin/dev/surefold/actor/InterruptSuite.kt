package dev.surefold.actor

import dev.surefold.Schedule
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import dev.surefold.scope
import dev.surefold.timeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

private const val ROUNDS = 5_000
private val SETTLE = 10.seconds

private sealed interface Chore

/** Done at once, after saying so. */
private data class Quick(val done: CountDownLatch) : Chore

/** Blocks until [released], after saying it has started. */
private data class Held(val started: CountDownLatch, val released: CountDownLatch) : Chore

/** Blocks until a latch nobody counts down, after saying it has started. */
private data class Forever(val started: CountDownLatch) : Chore

/** Raises, after saying it is about to. */
private data class Fumble(val raising: CountDownLatch) : Chore

private fun chores(log: AtomicReference<List<String>>) =
    behaviour<Chore, Unit, String>(Unit) { _, _, message ->
            when (message) {
                is Quick -> {
                    message.done.countDown()
                    stay()
                }

                is Held -> {
                    message.started.countDown()
                    message.released.await()
                    stay()
                }

                is Forever -> {
                    message.started.countDown()
                    CountDownLatch(1).await()
                    stay()
                }

                is Fumble -> {
                    message.raising.countDown()
                    fail("fumbled")
                }
            }
        }
        .onSignal { _, _, signal ->
            if (signal == Signal.Stopping) log.updateAndGet { it + "stopping" }
            stay()
        }

object InterruptSuite : Suite() {
    init {
        test("closing the scope interrupts a step blocked forever, and the actor still takes its Stopping") {
            val log = AtomicReference<List<String>>(emptyList())

            scope {
                val started = CountDownLatch(1)
                spawn("chores", chores(log)).tell(Forever(started))
                started.await()
            }

            log.get() shouldBe listOf("stopping")
        }

        test("closing the scope cuts a restart's delay short") {
            val log = AtomicReference<List<String>>(emptyList())

            scope {
                val raising = CountDownLatch(1)
                spawn("chores", chores(log), restart = Schedule.spaced(1.hours)).tell(Fumble(raising))
                raising.await()
            }

            log.get() shouldBe listOf("stopping")
        }

        test("a parent's stop interrupts a child blocked forever") {
            val log = AtomicReference<List<String>>(emptyList())

            scope {
                val started = CountDownLatch(1)
                val parent =
                    spawn(
                        "parent",
                        behaviour<Unit, Unit>(Unit) { ctx, _, _ ->
                            ctx.spawn("child", chores(log)).tell(Forever(started))
                            started.await()
                            stop()
                        },
                    )
                parent.tell(Unit)
                watch(parent).await()
            }

            log.get() shouldBe listOf("stopping")
        }

        // The second message, told as the first activation ends, starts a new one on another runner. The ending one
        // cleared the running thread after the new one had set its own, so a stop found no turn to interrupt.
        test("a stop interrupts a turn told as the actor's last activation was ending") {
            scope {
                repeat(ROUNDS) { round ->
                    val chores = spawn("chores-$round", chores(AtomicReference(emptyList())))
                    val done = CountDownLatch(1)
                    chores.tell(Quick(done))
                    while (done.count > 0) Thread.onSpinWait()
                    val started = CountDownLatch(1)
                    val released = CountDownLatch(1)
                    chores.tell(Held(started, released))
                    started.await()

                    val ended = timeoutOrNull(SETTLE) { stop(chores).await() } != null

                    // Let go by hand when the stop did not reach it, so the scope can still close.
                    released.countDown()
                    withClue("round $round: the stop interrupted the blocked turn, and the actor ended") {
                        ended shouldBe true
                    }
                    if (!ended) return@scope
                }
            }
        }
    }
}
