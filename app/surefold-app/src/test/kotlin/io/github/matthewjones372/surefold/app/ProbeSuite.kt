package io.github.matthewjones372.surefold.app

import dev.surefold.Clock
import dev.surefold.SystemClock
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.withClue
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.typeOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private class Broker
private class Consumer
private class Wedged

object ProbeSuite : Suite() {
    init {
        test("a dependent waits for the probe, not just the recipe") {
            val ready = AtomicBoolean()
            val sawReady = AtomicBoolean()
            val module = single<Broker> { Broker() }
                .probe("broker", timeout = 5.seconds) { _: Broker -> ready.set(true); true } +
                single { _: Broker -> sawReady.set(ready.get()); Consumer() }

            module.use { _: Consumer -> }.getOrNull().shouldNotBeNull()

            withClue("Consumer is built only after the broker answered") { sawReady.get() shouldBe true }
        }

        test("a probe that never passes fails the start, naming the node") {
            val module = single<Broker> { Broker() }.probe("broker", timeout = 5.seconds) { _: Broker -> false }

            module.use { _: Broker -> }.leftOrNull().shouldNotBeNull { error ->
                error.shouldBeInstanceOf<StartupError.Unready> { unready ->
                    unready.name shouldBe "broker"
                    unready.key shouldBe typeOf<Broker>()
                }
            }
        }

        test("a probe that always throws fails the start as unready, with the throw as its cause") {
            val module = single<Broker> { Broker() }
                .probe("broker", timeout = 5.seconds) { _: Broker -> error("connection refused") }

            module.use { _: Broker -> }.leftOrNull().shouldNotBeNull { error ->
                error.shouldBeInstanceOf<StartupError.Unready> { unready ->
                    unready.name shouldBe "broker"
                    unready.cause.shouldBeInstanceOf<IllegalStateException> { cause ->
                        cause.message shouldBe "connection refused"
                    }
                }
            }
        }

        test("a probe that hangs fails inside its timeout") {
            val forever = CountDownLatch(1)
            val module = single<Wedged> { Wedged() }
                .probe("wedged", timeout = 50.milliseconds) { _: Wedged -> forever.await(); true }

            module.use { _: Wedged -> }.leftOrNull().shouldNotBeNull { error ->
                error.shouldBeInstanceOf<StartupError.Unready> { unready -> unready.name shouldBe "wedged" }
            }
        }

        test("what was acquired before an unready node is given back") {
            val released = AtomicBoolean()
            val module = single<Broker> { install({ Broker() }) { _, _ -> released.set(true) } } +
                single<Consumer, Broker> { Consumer() }.probe("consumer", timeout = 5.seconds) { _: Consumer -> false }

            module.use { _: Consumer -> }.leftOrNull().shouldNotBeNull()

            released.get() shouldBe true
        }

        test("overriding a key drops the probe that was asked of it") {
            val asked = AtomicInteger()
            val module = (
                single<Broker> { Broker() }.probe("broker", timeout = 5.seconds) { _: Broker ->
                    asked.incrementAndGet()
                    false
                }
                ).overriding(single<Broker> { Broker() })

            module.use { _: Broker -> }.getOrNull().shouldNotBeNull()

            withClue("the fake is not the thing the probe was written about") { asked.get() shouldBe 0 }
        }

        test("a subgraph keeps only the probes of the nodes it kept") {
            val asked = AtomicBoolean()
            val module = single<Broker> { Broker() }
                .probe("broker", timeout = 5.seconds) { _: Broker -> asked.set(true); true } +
                single<Wedged> { Wedged() }

            testApp(module.subgraph<Wedged>()) { _: Wedged -> }

            asked.get() shouldBe false
        }
    }
}

/**
 * Instant up to [upTo], and the system clock past it.
 *
 * A probe waits out its retry interval on the inherited clock and `timeoutOrNull` races the ask
 * against a sleeper on the same one, so a clock that returns from every sleep makes the timeout a
 * coin flip: the sleeper has finished before the ask starts, and an answer that arrived is thrown
 * away often enough to lose an attempt. This waits out the interval and leaves the timeout a
 * timeout — the loser is interrupted as soon as the ask answers, so nothing waits it out.
 */
private fun impatientClock(upTo: Duration): Clock = object : Clock {

    override fun now(): Instant = Instant.EPOCH

    override fun sleep(duration: Duration) {
        if (duration > upTo) SystemClock.sleep(duration)
    }
}

object ProbeRetrySuite : Suite() {
    init {
        test("a probe that passes on a later attempt does not fail the start") {
            val asked = AtomicInteger()
            val module = single<Broker> { Broker() }
                .probe("broker", timeout = 5.seconds, attempts = 4, interval = 1.seconds) { _: Broker ->
                    asked.incrementAndGet() >= 3
                }

            clock.locally(impatientClock(1.seconds)) { module.use { _: Broker -> }.getOrNull().shouldNotBeNull() }

            asked.get() shouldBe 3
        }

        test("a probe that throws is asked again, like one that answered no") {
            val asked = AtomicInteger()
            val module = single<Broker> { Broker() }
                .probe("broker", timeout = 5.seconds, attempts = 3, interval = 1.seconds) { _: Broker ->
                    check(asked.incrementAndGet() >= 3) { "the broker is still starting" }
                    true
                }

            clock.locally(impatientClock(1.seconds)) { module.use { _: Broker -> }.getOrNull().shouldNotBeNull() }

            asked.get() shouldBe 3
        }

        test("a probe that never passes gives up after its attempts") {
            val asked = AtomicInteger()
            val module = single<Broker> { Broker() }
                .probe("broker", timeout = 5.seconds, attempts = 3, interval = 1.seconds) { _: Broker ->
                    asked.incrementAndGet(); false
                }

            clock.locally(impatientClock(1.seconds)) { module.use { _: Broker -> }.leftOrNull().shouldNotBeNull() }

            asked.get() shouldBe 3
        }
    }
}
