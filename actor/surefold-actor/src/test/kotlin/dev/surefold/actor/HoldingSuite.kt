package dev.surefold.actor

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe

/** Spec 0146: which of a durable producer's commands hold room, with no actor or semaphore. */
object HoldingSuite : Suite() {
    /** Outboxes recovered from a journal: a and b each keep two commands, and none holds room. */
    private val recovered = Outboxes<Unit>()
        .kept("a", 1, Unit).first.kept("a", 2, Unit).first
        .kept("b", 1, Unit).first.kept("b", 2, Unit).first

    init {
        test("a claim takes no more than the room there is, the first commands first") {
            val (holding, took) = Holding().claim(recovered, 3)
            took shouldBe 3
            holding.held shouldBe setOf("a" to 1L, "a" to 2L, "b" to 1L)
            holding.unheld(recovered) shouldBe 1
        }

        test("a claim passes over commands that already hold room") {
            val (holding, took) = Holding().kept("a", 1).claim(recovered, 10)
            took shouldBe 3
            holding.unheld(recovered) shouldBe 0
        }

        test("a confirmed command's room is handed to a recovered one waiting, not released") {
            val (claimed, _) = Holding().claim(recovered, 1)
            val after = recovered.confirmed("a", 1)!!.first
            val (holding, released) = claimed.confirmed(after, "a", 1)
            // Released, it would let a drain take every permit and answer while a-2 is still unconfirmed.
            released shouldBe false
            holding.held shouldBe setOf("a" to 2L)
        }

        test("a confirmed command's room is released once nothing waits for it") {
            val one = Outboxes<Unit>().kept("a", 1, Unit).first
            val (holding, released) = Holding().kept("a", 1).confirmed(one.confirmed("a", 1)!!.first, "a", 1)
            released shouldBe true
            holding shouldBe Holding()
        }

        test("a confirmed command that held no room releases none, and hands none on") {
            val after = recovered.confirmed("a", 1)!!.first
            val (holding, released) = Holding().confirmed(after, "a", 1)
            released shouldBe false
            holding shouldBe Holding()
        }
    }
}
