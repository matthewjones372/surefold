package io.github.matthewjones372.surefold.actor

import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.withClue
import dev.surefold.scope
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

private sealed interface Bulb

private data class Lit(val brightness: Int) : Bulb

private data object Off : Bulb

private sealed interface Switch

/** Lights the bulb, with a fade and a shimmer that belong to being lit. */
private data object On : Switch

/** A copy of the same state. */
private data object Brighter : Switch

/** A state of another class. */
private data object Flip : Switch

/** Asks for a state with timers, then stays instead. */
private data object Stray : Switch

private data object Fade : Switch

private data object Shimmer : Switch

private fun lamp(log: AtomicReference<List<String>>) = behaviour<Switch, Bulb>(Off) { ctx, bulb, message ->
    when (message) {
        On -> ctx.become(Lit(1)) {
            after(5.seconds, Fade)
            every(2.seconds, Shimmer)
        }

        Brighter -> if (bulb is Lit) become(bulb.copy(brightness = bulb.brightness + 1)) else unhandled()

        Flip -> become(Off)

        Stray -> {
            ctx.become(Lit(9)) { after(1.seconds, Fade) }
            stay()
        }

        Fade -> {
            log.updateAndGet { it + "fade" }
            stay()
        }

        Shimmer -> {
            log.updateAndGet { it + "shimmer" }
            stay()
        }
    }
}

/** What a test's lamp did, fresh for each test: the suite's tests share one object, and run at once. */
private fun freshLog() = AtomicReference<List<String>>(emptyList())

object ScopedSuite : Suite() {
    init {
        test("the timers a state starts with arrive while it lasts") {
            val log = freshLog()
            val lamp = lamp(log).test()

            lamp.send(On)
            lamp.advance(5.seconds)

            log.get() shouldContainExactly listOf("shimmer", "shimmer", "fade")
        }

        test("becoming a state of another class cancels its timers") {
            val log = freshLog()
            val lamp = lamp(log).test()
            lamp.send(On)
            lamp.advance(3.seconds)

            lamp.send(Flip)
            lamp.advance(1.hours)

            log.get() shouldContainExactly listOf("shimmer")
            lamp.pendingTimers shouldBe 0
        }

        test("a copy of the same state keeps them") {
            val log = freshLog()
            val lamp = lamp(log).test()
            lamp.send(On)

            lamp.send(Brighter)
            lamp.advance(5.seconds)

            lamp.state shouldBe Lit(2)
            log.get() shouldContainExactly listOf("shimmer", "shimmer", "fade")
        }

        test("becoming the state again with timers replaces the ones it had") {
            val log = freshLog()
            val lamp = lamp(log).test()
            lamp.send(On)
            lamp.advance(3.seconds)

            lamp.send(On)
            lamp.advance(5.seconds)

            withClue("the first fade, due at five, went with the timers it was replaced by") {
                log.get() shouldContainExactly listOf("shimmer", "shimmer", "shimmer", "fade")
            }
        }

        test("the timers of a become the step does not return never start") {
            val log = freshLog()
            val lamp = lamp(log).test()

            lamp.send(Stray)
            lamp.advance(1.hours)

            lamp.state shouldBe Off
            log.get().shouldBeEmpty()
            lamp.pendingTimers shouldBe 0
        }

        test("on threads, a state's timers last while it does and a copy keeps them") {
            val log = freshLog()
            val moving = TestClock()
            clock.locally(moving) {
                val _ = scope {
                    val lamp = spawn("lamp", lamp(log))
                    lamp.tell(On)
                    lamp.tell(Brighter)
                    awaitIdle()
                    moving.adjust(5.seconds)

                    lamp.tell(Flip)
                    awaitIdle()
                    moving.adjust(1.hours)
                }
            }

            log.get() shouldContainExactly listOf("shimmer", "shimmer", "fade")
        }
    }
}
