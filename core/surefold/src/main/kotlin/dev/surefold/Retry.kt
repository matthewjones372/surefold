package dev.surefold

import dev.surefold.Schedule.Decision.Continue
import dev.surefold.Schedule.Decision.Done
import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import dev.surefold.fail.nonFatalOrThrow

/** Runs [action] again on every throw the schedule accepts, and rethrows the last one when it ends. */
fun <A> Schedule<Throwable, *>.retry(action: () -> A): A = retryOrElse(action) { failure, _ -> throw failure }

/**
 * The same, answering with [orElse] of the last throw and the schedule's last output instead of rethrowing. A throw the
 * JVM cannot be asked to carry on from — and a `fail` passing through — is never retried.
 */
fun <Output, A> Schedule<Throwable, Output>.retryOrElse(action: () -> A, orElse: (Throwable, Output) -> A): A {
    var next = step
    while (true) {
        try {
            return action()
        } catch (failure: Throwable) {
            when (val decision = next(failure.nonFatalOrThrow())) {
                is Continue -> {
                    decision.delay.sleepOff()
                    next = decision.step
                }

                is Done -> return orElse(failure, decision.output)
            }
        }
    }
}

/** Runs [action] again on every failure the schedule accepts, and fails with the last one when it ends. */
fun <E, Output, A> Fail<E>.retry(schedule: Schedule<E, Output>, action: Fail<E>.() -> A): A {
    var next = schedule.step
    while (true) {
        val error =
            when (val tried = attempt(action)) {
                is Either.Right -> return tried.value
                is Either.Left -> tried.value
            }
        when (val decision = next(error)) {
            is Continue -> {
                decision.delay.sleepOff()
                next = decision.step
            }

            is Done -> fail(error)
        }
    }
}

/** The same retry with a boundary of its own, so the last failure is the `Left` rather than the caller's. */
fun <E, A, Output> Schedule<E, Output>.retrying(action: Fail<E>.() -> A): Either<E, A> = attempt {
    retry(this@retrying, action)
}
