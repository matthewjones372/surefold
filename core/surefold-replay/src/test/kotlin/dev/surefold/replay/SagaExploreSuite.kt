package dev.surefold.replay

import dev.surefold.Shared
import dev.surefold.fail.attempt
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import dev.surefold.parZip
import dev.surefold.saga

/**
 * Two branches each hold a seat and then wait on a fork of their own, so explore can put the other branch's fail
 * anywhere around them; whatever the order, each seat held is released exactly once and no other is.
 */
private fun holdTwoThenFail(): Pair<List<String>, List<String>> {
    val held = Shared(emptyList<String>())
    val released = Shared(emptyList<String>())
    attempt<String, Unit> {
        saga("t") {
            parZip(
                {
                    step("a", { _ -> held.update { it + "a" } }) { _, _, _ -> released.update { it + "a" } }
                    parZip({ }, { }) { _, _ -> }
                },
                {
                    parZip({ }, { }) { _, _ -> }
                    step("b", { _ -> held.update { it + "b" } }) { _, _, _ -> released.update { it + "b" } }
                    fail("full")
                },
            ) { _, _ -> }
        }
    }
    return held.peek() to released.peek()
}

object SagaExploreSuite : Suite() {
    init {
        test("in every order explored, a saga whose branch fails releases each step held exactly once") {
            explore(runs = 300) {
                val (held, released) = holdTwoThenFail()
                withClue("held $held, released $released") { released.sorted() shouldBe held.sorted() }
            }
        }
    }
}
