package io.github.matthewjones372.surefold.actor

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.scope
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.minutes

/** Asks the counter what it has counted. */
private data class CountAsked(val reply: Reply<Int>)

object CloseHookSuite : Suite() {
    init {
        test("a close hook asks an actor and has its answer before the scope stops it") {
            val answered = ConcurrentLinkedQueue<Int>()

            scope {
                val counted = behaviour<CountAsked, Int>(3) { _, count, asked -> stay().also { asked.reply(count) } }
                val counter = spawn("counter", counted)
                onClose { answered += counter.ask(1.minutes) { CountAsked(it) }.getOrNull()!! }
            }

            answered.toList() shouldBe listOf(3)
        }
    }
}
