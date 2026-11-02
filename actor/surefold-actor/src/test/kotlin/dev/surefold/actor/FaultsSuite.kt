package dev.surefold.actor

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldHaveSize
import dev.surefold.junit.shouldNotBeEmpty
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withClue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private fun pong(): Behaviour<ActorRef<String>, Int, Nothing> = behaviour(0) { _, answered, asker ->
    asker.tell("pong")
    become(answered + 1)
}

/** Asks [server] until it hears back, asking again each second. */
private fun asker(server: ActorRef<ActorRef<String>>): Behaviour<String, Boolean, Nothing> =
    behaviour(false) { ctx, answered, message ->
        when {
            message == "pong" -> {
                ctx.timers.cancel("again")
                become(true)
            }

            answered -> stay()

            else -> {
                server.tell(ctx.self)
                ctx.timers.after("again", 1.seconds, "again")
                stay()
            }
        }
    }

private fun echo(to: ActorRef<String>): Behaviour<String, Unit, Nothing> = behaviour(Unit) { _, _, word ->
    to.tell(word)
    stay()
}

private fun heard(): Behaviour<String, List<String>, Nothing> =
    behaviour(emptyList()) { _, seen, word -> become(seen + word) }

object FaultsSuite : Suite() {
    init {
        test("a requester that asks again completes when half of all messages are lost") {
            (1L..20L).forEach { seed ->
                testActors(order = Ordering.seeded(seed), faults = Faults(drop = 0.5)) {
                    val asker = spawn("asker", asker(spawn("server", pong())))
                    asker.tell("ask")
                    settle()
                    withClue("seed $seed: $trace") { asker.state shouldBe true }
                }
            }
        }

        test("the trace shows each message lost") {
            val dropped = (1L..20L).flatMap { seed ->
                testActors(order = Ordering.seeded(seed), faults = Faults(drop = 0.5)) {
                    spawn("asker", asker(spawn("server", pong()))).tell("ask")
                    settle()
                    trace.filterIsInstance<TraceEntry.Dropped>()
                }
            }
            dropped.shouldNotBeEmpty()
            dropped.map { it.message }.toSet().forEach { lost ->
                withClue(lost) { (lost == "pong" || lost is ActorRef<*>) shouldBe true }
            }
        }

        test("a duplicated message arrives twice") {
            testActors(order = Ordering.seeded(1), faults = Faults(duplicate = 1.0)) {
                val log = spawn("log", heard())
                spawn("echo", echo(log)).tell("hello")
                log.state shouldContainExactly listOf("hello", "hello")
                trace.filterIsInstance<TraceEntry.Duplicated>() shouldHaveSize 1
            }
        }

        test("a delayed message waits for the test's time to reach it") {
            testActors(order = Ordering.seeded(1), faults = Faults(delay = 1.0, maxDelay = 2.seconds)) {
                val log = spawn("log", heard())
                spawn("echo", echo(log)).tell("hello")
                log.state.shouldBeEmpty()
                val delayed = trace.filterIsInstance<TraceEntry.Delayed>().single()
                withClue(delayed) { (delayed.by > Duration.ZERO && delayed.by <= 2.seconds) shouldBe true }
                settle()
                log.state shouldContainExactly listOf("hello")
            }
        }

        test("an actor's tells to itself are never lost, as they never leave its node") {
            testActors(order = Ordering.seeded(1), faults = Faults(drop = 1.0)) {
                val counter = spawn(
                    "counter",
                    behaviour<Int, Int>(0) { ctx, seen, left ->
                        if (left > 0) ctx.self.tell(left - 1)
                        become(seen + 1)
                    },
                )
                counter.tell(5)
                counter.state shouldBe 6
            }
        }

        test(
            "a delayed message holds back what its sender tells the same receiver after it, as one connection would",
        ) {
            (1L..50L).forEach { seed ->
                testActors(order = Ordering.seeded(seed), faults = Faults(delay = 0.5, maxDelay = 2.seconds)) {
                    val log = spawn("log", heard())
                    spawn(
                        "burst",
                        behaviour<String, Unit>(Unit) { _, _, _ ->
                            listOf("1", "2", "3").forEach(log::tell)
                            stay()
                        },
                    ).tell("go")
                    settle()
                    withClue("seed $seed: $trace") { log.state shouldContainExactly listOf("1", "2", "3") }
                }
            }
        }

        test("a tell from the test itself is never lost") {
            testActors(order = Ordering.seeded(1), faults = Faults(drop = 1.0)) {
                val log = spawn("log", heard())
                log.tell("hello")
                log.state shouldContainExactly listOf("hello")
            }
        }

        test("faults draw from the order's seed, so they need one") {
            shouldThrow<IllegalArgumentException> { testActors(faults = Faults(drop = 0.1)) { } }
        }
    }
}
