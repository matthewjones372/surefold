package dev.surefold.actor

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeNull
import dev.surefold.junit.shouldContainExactly

/** Spec 0146: the outbox rules of spec 0079, with no actor, room or clock. */
object OutboxesSuite : Suite() {
    private fun Outboxes<String>.keeping(to: String, sequence: Long) = kept(to, sequence, "$to-$sequence").first

    init {
        test("an entity's first command is sent, and one kept behind it is not") {
            val (once, first) = Outboxes<String>().kept("a", 1, "a-1")
            val (_, second) = once.kept("a", 2, "a-2")
            first shouldBe Send("a", 1, "a-1")
            second.shouldBeNull()
        }

        test("each entity has a command in flight of its own") {
            val (_, other) = Outboxes<String>().keeping("a", 1).kept("b", 1, "b-1")
            other shouldBe Send("b", 1, "b-1")
        }

        test("a confirm for the head sends the next") {
            val (after, next) = Outboxes<String>().keeping("a", 1).keeping("a", 2).confirmed("a", 1)!!
            next shouldBe Send("a", 2, "a-2")
            after.heads() shouldContainExactly listOf(Send("a", 2, "a-2"))
        }

        test("a confirm for anything but the head is ignored") {
            val outboxes = Outboxes<String>().keeping("a", 1).keeping("a", 2)
            outboxes.confirmed("a", 2).shouldBeNull()
            outboxes.confirmed("b", 1).shouldBeNull()
        }

        test("a confirm for the last command sends nothing and leaves the outbox empty") {
            val (after, next) = Outboxes<String>().keeping("a", 1).confirmed("a", 1)!!
            next.shouldBeNull()
            after shouldBe Outboxes()
        }

        test("a resend sends every entity's head, and nothing behind it") {
            val outboxes = Outboxes<String>().keeping("a", 1).keeping("a", 2).keeping("b", 7)
            outboxes.heads() shouldContainExactly listOf(Send("a", 1, "a-1"), Send("b", 7, "b-7"))
        }
    }
}
