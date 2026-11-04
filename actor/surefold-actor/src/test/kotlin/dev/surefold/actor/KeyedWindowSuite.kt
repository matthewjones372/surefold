package dev.surefold.actor

import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

private object Pence : StateCodec<Long> {
    override fun encode(state: Long): ByteArray = state.toString().toByteArray()

    override fun decode(bytes: ByteArray): Long = String(bytes).toLong()
}

object KeyedWindowSuite : Suite() {
    init {
        test("a key older than the window is forgotten once another answer is written") {
            val time = TestClock()
            clock.locally(time) {
                testActors {
                    val a = spawn("a", account("a", remember = 1.days))
                    a.ask { Keyed.Apply("t-1/credit", Move.Credit(1_000), it) }

                    time.adjust(25.hours)
                    a.ask { Keyed.Apply("t-2/credit", Move.Credit(1), it) }

                    a.ask<KeyAnswer<Funds>> { Keyed.Applied("t-1/credit", it) } shouldBe KeyAnswer.Unknown.right()
                    a.ask<KeyAnswer<Funds>> { Keyed.Applied("t-2/credit", it) } shouldBe KeyAnswer.Accepted.right()
                }
            }
        }

        test("a key inside the window is still answered as it was, and applied once") {
            val time = TestClock()
            clock.locally(time) {
                testActors {
                    val a = spawn("a", account("a", remember = 1.days))
                    a.ask { Keyed.Apply("t-1/credit", Move.Credit(1_000), it) }

                    time.adjust(23.hours)
                    a.ask { Keyed.Apply("t-1/credit", Move.Credit(1_000), it) } shouldBe KeyAnswer.Accepted.right()

                    a.state.value.value shouldBe 1_000L
                }
            }
        }

        test("forgetting is the same on replay: a restart remembers exactly the keys it did before") {
            val time = TestClock()
            clock.locally(time) {
                testActors {
                    val a = spawn("a", account("a", remember = 1.days))
                    a.ask { Keyed.Apply("t-1/credit", Move.Credit(1_000), it) }
                    time.adjust(25.hours)
                    a.ask { Keyed.Apply("t-2/debit", Move.Debit(5_000), it) }
                    val before = a.state.value.answers.keys

                    a.restart()

                    a.state.value.answers.keys shouldBe before
                    a.ask<KeyAnswer<Funds>> { Keyed.Applied("t-2/debit", it) } shouldBe KeyAnswer.Refused(Funds).right()
                }
            }
        }

        test("a snapshot keeps the keys and their answers, and a start from it answers them") {
            testActors {
                val a = spawn("a", account("a", snapshots = every(2, Pence)))
                a.ask { Keyed.Apply("t-1/credit", Move.Credit(1_000), it) }
                a.ask { Keyed.Apply("t-2/debit", Move.Debit(5_000), it) }

                a.restart()

                a.ask<KeyAnswer<Funds>> { Keyed.Applied("t-1/credit", it) } shouldBe KeyAnswer.Accepted.right()
                a.ask<KeyAnswer<Funds>> { Keyed.Applied("t-2/debit", it) } shouldBe KeyAnswer.Refused(Funds).right()
                a.state.value.value shouldBe 1_000L
            }
        }
    }
}
