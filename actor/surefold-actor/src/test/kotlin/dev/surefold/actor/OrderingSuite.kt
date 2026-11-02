package dev.surefold.actor

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeGreaterThan
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withClue
import kotlin.time.Duration.Companion.seconds

private fun log(): Behaviour<String, List<String>, Nothing> =
    behaviour(emptyList()) { _, seen, word -> become(seen + word) }

private fun burst(to: ActorRef<String>): Behaviour<String, Unit, Nothing> = behaviour(Unit) { _, _, prefix ->
    (1..3).forEach { to.tell("$prefix$it") }
    stay()
}

private fun fanOut(vararg to: ActorRef<String>): Behaviour<String, Unit, Nothing> = behaviour(Unit) { _, _, _ ->
    to.forEachIndexed { i, ref -> ref.tell("${'a' + i}") }
    stay()
}

private class Logged(val words: List<String>, val trace: List<TraceEntry>)

private fun heard(order: Ordering = Ordering.Told): Logged = testActors(order = order) {
    val log = spawn("log", log())
    spawn("go", fanOut(spawn("a", burst(log)), spawn("b", burst(log)))).tell("go")
    Logged(log.state, trace)
}

object OrderingSuite : Suite() {
    init {
        test("without an order every message runs in the order it was told") {
            heard().words shouldContainExactly listOf("a1", "a2", "a3", "b1", "b2", "b3")
        }

        test("seeds give different interleavings, each keeping every sender's own order") {
            val runs = (1L..20L).map { heard(Ordering.seeded(it)).words }
            runs.toSet().size shouldBeGreaterThan 1
            runs.forEach { words ->
                withClue(words) {
                    words.filter { it.startsWith("a") } shouldContainExactly listOf("a1", "a2", "a3")
                    words.filter { it.startsWith("b") } shouldContainExactly listOf("b1", "b2", "b3")
                }
            }
        }

        test("one seed gives the same trace every time") {
            val first = heard(Ordering.seeded(42)).trace
            heard(Ordering.seeded(42)).trace shouldBe first
        }

        test("the trace names who told what to whom") {
            val trace = heard().trace.filterIsInstance<TraceEntry.Delivered>()
            trace.first() shouldBe TraceEntry.Delivered(null, Address("test", "/user/go", 4), "go")
            trace.filter { it.to.path == "/user/log" }.map { it.from?.path }.toSet() shouldBe
                setOf("/user/a", "/user/b")
        }

        test("settle runs timers to idle, moving time on to each") {
            testActors {
                val log = spawn("log", log())
                val later = spawn(
                    "later",
                    behaviour<String, Unit>(Unit) { ctx, _, word ->
                        if (word == "now") ctx.timers.after("later", 5.seconds, "later")
                        log.tell(word)
                        stay()
                    },
                )
                later.tell("now")
                settle()
                log.state shouldContainExactly listOf("now", "later")
                pendingTimers shouldBe 0
            }
        }

        test("settle fails rather than run forever when something never goes idle") {
            testActors {
                val ticker = spawn(
                    "ticker",
                    behaviour<String, Int>(0) { ctx, ticks, _ ->
                        ctx.timers.every("tick", 1.seconds, "tick")
                        become(ticks + 1)
                    },
                )
                ticker.tell("start")
                shouldThrow<IllegalStateException>({ settle(maxSteps = 50) }) { e -> e.message shouldContain "50" }
            }
        }
    }
}
