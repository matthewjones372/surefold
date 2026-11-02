package io.github.matthewjones372.surefold.actor

import dev.surefold.capturingLogs
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.logAnnotated
import dev.surefold.logInfo
import dev.surefold.scope
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration

/** The request a line written here would be annotated with, or "" when nothing bound one. */
private fun requestHere(): String = capturingLogs { logs ->
    logInfo("handling")
    logs.all().single().annotations["request_id"].orEmpty()
}

/** Spec 0223: a plain behaviour handed the messages waiting as one run, each with its own carried context. */
object BatchingSuite : Suite() {
    init {

        test("the messages already waiting are handed over as one run, in order") {
            val seen = CopyOnWriteArrayList<List<Int>>()
            val opened = CountDownLatch(1)
            val runs = batching<Int, Unit>(Unit, batch = 64) { _, _, run ->
                seen += run.toList()
                stay()
            }.onStart { opened.await() }
            val _ = scope {
                val actor = spawn("runs", runs)
                (1..3).forEach(actor::tell)
                opened.countDown()
                awaitIdle()
            }
            seen shouldBe listOf(listOf(1, 2, 3))
        }

        test("each message of a run is within its own sender's context, and the run within none") {
            val inside = CopyOnWriteArrayList<String>()
            val outside = CopyOnWriteArrayList<String>()
            val opened = CountDownLatch(1)
            val runs = batching<Int, Unit>(Unit, batch = 64) { _, _, run ->
                outside += requestHere()
                run.indices.forEach { i -> inside += run.within(i) { requestHere() } }
                stay()
            }.onStart { opened.await() }
            val _ = scope {
                val actor = spawn("runs", runs)
                logAnnotated("request_id" to "r-1") { actor.tell(1) }
                actor.tell(2)
                logAnnotated("request_id" to "r-3") { actor.tell(3) }
                opened.countDown()
                awaitIdle()
            }
            inside shouldBe listOf("r-1", "", "r-3")
            outside shouldBe listOf("")
        }

        test("a message handled alone is a run of one, in its own sender's context") {
            val seen = CopyOnWriteArrayList<Pair<List<Int>, String>>()
            val runs = batching<Int, Unit>(Unit, batch = 1) { _, _, run ->
                seen += run.toList() to requestHere()
                stay()
            }
            val _ = scope {
                val actor = spawn("runs", runs)
                (1..3).forEach { n -> logAnnotated("request_id" to "r-$n") { actor.tell(n) } }
                awaitIdle()
            }
            seen shouldBe listOf(listOf(1) to "r-1", listOf(2) to "r-2", listOf(3) to "r-3")
        }

        test("a timer's message reaches the same step as a run of one") {
            val seen = CopyOnWriteArrayList<List<Int>>()
            val runs = batching<Int, Unit>(Unit, batch = 64) { _, _, run ->
                seen += run.toList()
                stop()
            }.onStart { ctx -> ctx.timers.after("soon", Duration.ZERO, 7) }
            val _ = scope {
                watch(spawn("runs", runs)).await()
            }
            seen shouldBe listOf(listOf(7))
        }

        test("a run that stops is heard as a stop by its signal handler") {
            val heard = CopyOnWriteArrayList<Signal>()
            val runs = batching<Int, Unit>(Unit, batch = 64) { _, _, _ -> stop() }
                .onSignal { _, _, signal -> stay().also { heard += signal } }
            val _ = scope {
                val actor = spawn("runs", runs)
                actor.tell(1)
                watch(actor).await()
            }
            heard shouldBe listOf<Signal>(Signal.Stopping)
        }

        test("a run answered unhandled sends every message of it to dead letters") {
            val letters = CopyOnWriteArrayList<DeadLetter>()
            val opened = CountDownLatch(1)
            val runs = batching<Int, Unit>(Unit, batch = 64) { _, _, _ -> unhandled() }.onStart { opened.await() }
            val _ = scope {
                onDeadLetter(letters::add)
                val actor = spawn("runs", runs)
                (1..3).forEach(actor::tell)
                opened.countDown()
                awaitIdle()
            }
            letters.map { it.message to it.why } shouldBe (1..3).map { it to DeadLetter.Why.Unhandled }
        }

        test("a raise from a run is the actor's declared failure") {
            val runs =
                batching<Int, Unit, String>(Unit, batch = 64) { _, _, run -> fail("refused ${run.toList()}") }.test()

            runs.send(1)

            runs.failure shouldBe Failure.Declared("refused [1]")
        }
    }
}
