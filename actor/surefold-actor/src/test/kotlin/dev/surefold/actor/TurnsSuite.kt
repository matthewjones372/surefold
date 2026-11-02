package dev.surefold.actor

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.scope
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration

/** Spec 0086: a behaviour with `turns` is handed the plain messages already waiting, a run at a time. */
object TurnsSuite : Suite() {
    /** Every run it is handed, and every message handed it alone, in the order they came. */
    private class Runs(
        val batch: Int,
        val opened: CountDownLatch = CountDownLatch(1),
        val armed: CountDownLatch? = null,
        val told: CountDownLatch = CountDownLatch(0),
    ) {
        val seen = CopyOnWriteArrayList<List<Int>>()

        fun behaviour(): Behaviour<Int, Unit, Nothing> = Behaviour(
            initial = Unit,
            turn = { _, _, n ->
                seen += listOf(n)
                // The timer's message holds the actor until the test has told what comes after it.
                if (n == 0) told.await()
                stay()
            },
            signal = null,
            start = { ctx, _ ->
                opened.await()
                if (armed != null) {
                    ctx.timers.after("between", Duration.ZERO, 0)
                    armed.countDown()
                }
                stay()
            },
            turns = { _, _, run ->
                seen += run
                Batched(stay(), emptyList(), emptyList())
            },
            batch = batch,
        )
    }

    init {
        test("the messages already waiting are handed over as one run, in order") {
            val runs = Runs(batch = 64)
            scope {
                val actor = spawn("runs", runs.behaviour())
                (1..3).forEach(actor::tell)
                runs.opened.countDown()
                awaitIdle()
            }
            runs.seen shouldBe listOf(listOf(1, 2, 3))
        }

        test("a run is no longer than the batch") {
            val runs = Runs(batch = 2)
            scope {
                val actor = spawn("runs", runs.behaviour())
                (1..5).forEach(actor::tell)
                runs.opened.countDown()
                awaitIdle()
            }
            runs.seen shouldBe listOf(listOf(1, 2), listOf(3, 4), listOf(5))
        }

        test("a timer between messages ends the run, and is handled alone") {
            val runs = Runs(batch = 64, armed = CountDownLatch(1), told = CountDownLatch(1))
            scope {
                val actor = spawn("runs", runs.behaviour())
                actor.tell(1)
                actor.tell(2)
                runs.opened.countDown()
                runs.armed?.await()
                actor.tell(3)
                actor.tell(4)
                runs.told.countDown()
                awaitIdle()
            }
            runs.seen shouldBe listOf(listOf(1, 2), listOf(0), listOf(3, 4))
        }

        test("a behaviour that does not batch still takes one message at a time") {
            val seen = CopyOnWriteArrayList<Int>()
            val opened = CountDownLatch(1)
            val one = Behaviour<Int, Unit, Nothing>(
                initial = Unit,
                turn = { _, _, n ->
                    seen += n
                    stay()
                },
                start = { _, _ ->
                    opened.await()
                    stay()
                },
            )
            scope {
                val actor = spawn("one", one)
                (1..3).forEach(actor::tell)
                opened.countDown()
                awaitIdle()
            }
            seen shouldBe listOf(1, 2, 3)
        }

        test("a behaviour's step, kept under its old name, is its turn") {
            val one = behaviour<Int, Int>(0) { _, total, n -> become(total + n) }

            @Suppress("DEPRECATION")
            val step = one.step
            step shouldBe one.turn
        }

        test("what a run leaves unhandled or unrun becomes a dead letter") {
            val letters = CopyOnWriteArrayList<DeadLetter>()
            val opened = CountDownLatch(1)
            val stopping = Behaviour<Int, Unit, Nothing>(
                initial = Unit,
                turn = { _, _, _ -> stay() },
                signal = null,
                start = { _, _ ->
                    opened.await()
                    stay()
                },
                turns = { _, _, run -> Batched(stop(), unhandled = listOf(run[0]), unrun = run.drop(2)) },
                batch = 64,
            )
            scope {
                onDeadLetter(letters::add)
                val actor = spawn("stopping", stopping)
                (1..4).forEach(actor::tell)
                opened.countDown()
                watch(actor).await()
            }
            letters.map { it.message to it.why } shouldBe listOf(
                1 to DeadLetter.Why.Unhandled,
                3 to DeadLetter.Why.Stopped,
                4 to DeadLetter.Why.Stopped,
            )
        }
    }
}
