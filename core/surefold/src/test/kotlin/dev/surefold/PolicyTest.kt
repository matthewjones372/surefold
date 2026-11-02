package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeLeft
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldContainExactly
import dev.surefold.assert.shouldThrow
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import dev.surefold.fail.left
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Time moves only when the thread that built it sleeps. A timeout's sleeper is a fork, so with [forksWait] it never
 * wakes and a fast attempt always beats it; without, it wakes at once and a parked attempt always loses.
 */
internal class JumpingClock(private val forksWait: Boolean) : Clock {
    private val owner = Thread.currentThread()
    private val at = AtomicReference(Instant.EPOCH)

    override fun now(): Instant = at.get()

    override fun sleep(duration: Duration) {
        if (forksWait && Thread.currentThread() != owner) Thread.sleep(NEVER_FINISHES_MILLIS)
        at.updateAndGet { it.plusNanos(duration.inWholeNanoseconds) }
    }

    fun elapsed(): Duration = at.get().toEpochMilli().milliseconds
}

private class Tracing(private val label: String, private val trace: MutableList<String>) : Guard {
    override fun <E, A> Fail<E>.guard(block: Fail<E>.() -> A): A {
        trace += label
        return block()
    }
}

class PolicyTest {

    @Test
    fun `a policy built in the wrong order throws naming the expected order`() {
        val wrong = shouldThrow<IllegalArgumentException> {
            policy("partner") {
                attemptTimeout(1.seconds)
                retry(Schedule.recurs(3))
            }
        }

        wrong.message shouldContain "retry comes after attemptTimeout"
        wrong.message shouldContain "deadline → retry → breaker → limiter → bulkhead → attemptTimeout"
    }

    @Test
    fun `a repeated step and an attempt timeout past the deadline are refused`() {
        shouldThrow<IllegalArgumentException> {
            policy("partner") {
                retry(Schedule.recurs(1))
                retry(Schedule.recurs(1))
            }
        }.message shouldContain "retry appears twice"

        shouldThrow<IllegalArgumentException> {
            policy("partner") {
                deadline(1.seconds)
                attemptTimeout(2.seconds)
            }
        }.message shouldContain "longer than its deadline"
    }

    @Test
    fun `a custom guard goes anywhere and wraps what follows it`() {
        val trace = CopyOnWriteArrayList<String>()
        val partner = policy("partner") {
            guard(Tracing("outer", trace))
            retry(Schedule.recurs(1))
            guard(Tracing("inner", trace))
        }

        shouldThrow<Boom> { partner { throw Boom() } }

        partner.steps.map { it::class.simpleName } shouldContainExactly listOf("Custom", "Retry", "Custom")
        trace shouldContainExactly listOf("outer", "inner", "inner")
    }

    @Test
    fun `retry stops before a delay that would overrun the deadline`() {
        val time = JumpingClock(forksWait = true)
        val attempts = AtomicInteger()
        val partner = policy("partner") {
            deadline(1.seconds)
            retry(Schedule.spaced(400.milliseconds))
        }

        shouldThrow<Boom> {
            clock.locally(time) {
                partner {
                    attempts.incrementAndGet()
                    throw Boom()
                }
            }
        }

        attempts.get() shouldBe 3
        time.elapsed() shouldBe 800.milliseconds
    }

    @Test
    fun `each attempt is cut to the remaining budget`() {
        val time = JumpingClock(forksWait = false)
        val attempts = AtomicInteger()
        val partner = policy("partner") {
            deadline(1.seconds)
            retry(Schedule.spaced(100.milliseconds))
            attemptTimeout(300.milliseconds)
        }

        shouldThrow<TimeoutException> {
            clock.locally(time) {
                partner {
                    attempts.incrementAndGet()
                    Sleeper().body()
                }
            }
        }

        attempts.get() shouldBe 3
        time.elapsed() shouldBe 1.seconds
    }

    @Test
    fun `a fail under attempt passes through every step uncounted`() {
        val attempts = AtomicInteger()
        val partner = policy("partner") {
            deadline(1.seconds)
            retry(Schedule.recurs(3))
            attemptTimeout(1.seconds)
        }

        attempt<Bad, Int> {
            guarded(partner, ifRejected = { Bad("rejected") }) {
                attempts.incrementAndGet()
                fail(Bad("declared"))
            }
        } shouldBe Bad("declared").left()

        attempts.get() shouldBe 1
    }

    @Test
    fun `a fail in a scope passes through every step uncounted`() {
        val attempts = AtomicInteger()
        val partner = policy("partner") {
            deadline(1.seconds)
            retry(Schedule.recurs(3))
            attemptTimeout(1.seconds)
        }

        attempt<Bad, Int> {
            scope {
                guarded(partner, ifRejected = { Bad("rejected") }) {
                    attempts.incrementAndGet()
                    fail(Bad("declared"))
                }
            }
        } shouldBeLeft Bad("declared")

        attempts.get() shouldBe 1
    }

    @Test
    fun `guarded turns a rejection into the declared error`() {
        val refusing = object : Guard {
            override fun <E, A> Fail<E>.guard(block: Fail<E>.() -> A): A =
                throw Rejected.RateLimited("partner", 2.seconds)
        }
        val partner = policy("partner") { guard(refusing) }

        attempt<Bad, Int> {
            guarded(partner, ifRejected = { Bad("${it.guard}: ${it.message}") }) { 42 }
        } shouldBe Bad("partner: partner has no token for 2s").left()
    }

    @Test
    fun `each call is counted once under its policy, by outcome`() {
        val partner = policy("partner") { retry(Schedule.recurs(2)) }

        capturingMetrics { measured ->
            partner { 42 }
            measured.counter("surefold.policy.calls") shouldBe 1.0
            measured.tags("surefold.policy.calls") shouldBe
                mapOf("policy" to "partner", "outcome" to "success", "refused_by" to "none")
        }

        capturingMetrics { measured ->
            shouldThrow<Boom> { partner { throw Boom() } }
            measured.counter("surefold.policy.calls") shouldBe 1.0
            measured.tags("surefold.policy.calls")["outcome"] shouldBe "failure"
        }
    }

    @Test
    fun `a refusal is counted by the guard that refused`() {
        val refusals = mapOf(
            "breaker" to Rejected.CircuitOpen("partner", Instant.EPOCH),
            "limiter" to Rejected.RateLimited("partner", 1.seconds),
            "bulkhead" to Rejected.BulkheadFull("partner"),
        )

        refusals.forEach { (by, refusal) ->
            val partner = policy("partner") {
                guard(
                    object : Guard {
                        override fun <E, A> Fail<E>.guard(block: Fail<E>.() -> A): A = throw refusal
                    },
                )
            }

            capturingMetrics { measured ->
                shouldThrow<Rejected> { partner { 42 } }
                measured.tags("surefold.policy.calls") shouldBe
                    mapOf("policy" to "partner", "outcome" to "rejected", "refused_by" to by)
            }
        }
    }

    @Test
    fun `a call the deadline ends is counted as refused by the deadline`() {
        val time = JumpingClock(forksWait = false)
        val partner = policy("partner") { deadline(1.seconds) }

        capturingMetrics { measured ->
            shouldThrow<TimeoutException> { clock.locally(time) { partner { Sleeper().body() } } }
            measured.tags("surefold.policy.calls")["refused_by"] shouldBe "deadline"
        }
    }

    @Test
    fun `an attempt timeout inside the deadline is a failure, not the deadline`() {
        val time = JumpingClock(forksWait = false)
        val partner = policy("partner") {
            deadline(1.seconds)
            attemptTimeout(100.milliseconds)
        }

        capturingMetrics { measured ->
            shouldThrow<TimeoutException> { clock.locally(time) { partner { Sleeper().body() } } }
            measured.tags("surefold.policy.calls")["outcome"] shouldBe "failure"
        }
    }
}
