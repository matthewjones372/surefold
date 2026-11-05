package dev.surefold

import dev.surefold.CircuitBreaker.Admission.Admitted
import dev.surefold.CircuitBreaker.Admission.Refused
import dev.surefold.Schedule.Decision.Continue
import dev.surefold.Schedule.Decision.Done
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/**
 * Stops calling something that is down (spec 0111). After [maxFailures] failures in a row it refuses every call until
 * the next delay of [resetAfter] has passed, then lets one trial through. It is called through a [Policy].
 */
class CircuitBreaker(
    val name: String,
    private val maxFailures: Int,
    private val resetAfter: Schedule<Unit, *>,
    internal val countsAsFailure: (Throwable) -> Boolean = { it !is Rejected },
) {
    init {
        require(maxFailures >= 1) { "Breaker \"$name\" must open after at least one failure, not $maxFailures." }
    }

    sealed interface State {
        data class Closed(val failures: Int) : State

        /** Refusing every call until [until]. The first call after it is the trial. */
        data class Open(val until: Instant) : State

        data object HalfOpen : State
    }

    // Where resetAfter has got to travels with the state: it starts again only when a trial closes the breaker.
    private data class Held(val state: State, val reset: ScheduleStep<Unit, *>, val lastDelay: Duration)

    private val held = AtomicReference(Held(State.Closed(0), resetAfter.step, ZERO))

    val state: State
        get() = held.get().state

    internal sealed interface Admission {
        data class Admitted(val trial: Boolean) : Admission

        data class Refused(val retryAt: Instant) : Admission
    }

    internal enum class Result {
        SUCCESS,
        FAILURE,
        UNCOUNTED,
    }

    internal fun admit(): Admission {
        while (true) {
            val seen = held.get()
            val now = clock.get().now()
            when (val state = seen.state) {
                is State.Closed -> return Admitted(trial = false)

                // The soonest a failed trial would let the next one through.
                is State.HalfOpen -> return Refused(now.plusNanos(seen.lastDelay.inWholeNanoseconds))

                is State.Open ->
                    if (now < state.until) {
                        return Refused(state.until)
                    } else if (held.compareAndSet(seen, seen.copy(state = State.HalfOpen))) {
                        publish(State.HalfOpen)
                        return Admitted(trial = true)
                    }
            }
        }
    }

    internal fun record(admitted: Admitted, result: Result) {
        publish(held.updateAndGet { seen -> after(seen, admitted.trial, result) }.state)
        when (result) {
            Result.SUCCESS -> count("success")
            Result.FAILURE -> count("failure")
            Result.UNCOUNTED -> Unit
        }
    }

    internal fun refused(retryAt: Instant): Rejected.CircuitOpen {
        count("rejected")
        return Rejected.CircuitOpen(name, retryAt)
    }

    private fun count(outcome: String) =
        counter("surefold.breaker.calls", "name" to name, "outcome" to outcome).increment()

    private fun publish(state: State) =
        gauge("surefold.breaker.state", "name" to name)
            .set(
                when (state) {
                    is State.Closed -> 0.0
                    is State.HalfOpen -> 1.0
                    is State.Open -> 2.0
                },
            )

    private fun after(seen: Held, trial: Boolean, result: Result): Held {
        val state = seen.state
        return when {
            trial ->
                when (result) {
                    Result.SUCCESS -> Held(State.Closed(0), resetAfter.step, ZERO)

                    Result.FAILURE -> opened(seen)

                    // A trial that proved nothing hands the trial on to the next caller.
                    Result.UNCOUNTED -> seen.copy(state = State.Open(clock.get().now()))
                }

            // A call let through before the breaker opened, answering after it did.
            state !is State.Closed -> seen

            result == Result.SUCCESS -> seen.copy(state = State.Closed(0))

            result == Result.FAILURE && state.failures + 1 >= maxFailures -> opened(seen)

            result == Result.FAILURE -> seen.copy(state = State.Closed(state.failures + 1))

            else -> seen
        }
    }

    private fun opened(seen: Held): Held {
        val (delay, reset) =
            when (val decision = seen.reset(Unit)) {
                is Continue -> decision.delay to decision.step
                is Done -> seen.lastDelay to seen.reset
            }
        return Held(State.Open(clock.get().now().plusNanos(delay.inWholeNanoseconds)), reset, delay)
    }
}
