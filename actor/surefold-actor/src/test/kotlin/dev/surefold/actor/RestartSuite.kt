package dev.surefold.actor

import dev.surefold.Schedule
import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.fail.left
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeRight
import dev.surefold.junit.shouldThrow
import dev.surefold.scope
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private data object Jammed

private sealed interface Press

private data class Stamp(val n: Int) : Press

private data object Jam : Press

private data object Crack : Press

private data class Stamped(val reply: Reply<List<Int>>) : Press

/** Collects stamps; a `Jam` raises and a `Crack` throws, and either loses what was collected. */
private fun press() =
    behaviour<Press, List<Int>, Jammed>(emptyList()) { _, stamped, message ->
        when (message) {
            is Stamp -> become(stamped + message.n)

            Jam -> fail(Jammed)

            Crack -> error("the press cracked")

            is Stamped -> {
                message.reply(stamped)
                stay()
            }
        }
    }

object RestartSuite : Suite() {
    init {
        test("a raise restarts the actor from its initial state, and it carries on") {
            val press = press().test(restart = Schedule.recurs(3))

            press.send(Stamp(1))
            press.send(Jam)
            press.send(Stamp(2))

            press.stopped shouldBe false
            press.state shouldBe listOf(2)
            press.restarts shouldBe 1
        }

        test("a throw restarts it on the same schedule, and does not reach the test") {
            val press = press().test(restart = Schedule.recurs(3))

            press.send(Crack)

            press.stopped shouldBe false
            press.restarts shouldBe 1
        }

        test("when the schedule is done the actor stops, with the failure that ended it") {
            val press = press().test(restart = Schedule.recurs(1))

            press.send(Jam)
            press.send(Jam)

            press.stopped shouldBe true
            press.restarts shouldBe 1
            press.failure shouldBe Failure.Declared(Jammed)
        }

        test("the test kit records each restart's delay rather than waiting it out") {
            val press = press().test(restart = Schedule.exponential(100.milliseconds))

            repeat(3) { press.send(Jam) }

            press.delays shouldBe listOf(100.milliseconds, 200.milliseconds, 400.milliseconds)
        }

        test("with no schedule a throw still stops the actor and reaches the test") {
            val press = press().test()

            shouldThrow<IllegalStateException> { press.send(Crack) }

            press.stopped shouldBe true
        }

        test("on threads, a restart waits on the scope's clock and the mailbox is intact") {
            val time = TestClock()
            val stamped =
                clock.locally(time) {
                    scope {
                        val press = spawn("press", press(), restart = Schedule.spaced(1.hours))
                        press.tell(Stamp(1))
                        press.tell(Jam)
                        press.tell(Stamp(2))
                        press.tell(Stamp(3))
                        time.adjustWhenBlocked(1.hours)
                        press.ask(1.minutes) { Stamped(it) }
                    }
                }

            stamped shouldBeRight listOf(2, 3)
        }

        test("on threads, a schedule that is done stops the actor") {
            val answer = scope {
                val press = spawn("press", press(), restart = Schedule.recurs(0))
                press.tell(Jam)
                press.ask(1.minutes) { Stamped(it) }
            }

            answer shouldBe AskFailure.Stopped.left()
        }
    }
}
