package dev.surefold

import dev.surefold.Schedule.Decision.Continue
import dev.surefold.Schedule.Decision.Done
import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.toKotlinDuration

/** One step of a schedule: what it decides about an input, and how it carries on from there. */
typealias ScheduleStep<Input, Output> = (Input) -> Schedule.Decision<Input, Output>

/**
 * How something should be repeated or retried: a step that answers each [Input] with a [Decision] to carry
 * on after a delay, or to stop with an [Output].
 */
fun interface Schedule<in Input, out Output> {

    operator fun invoke(input: Input): Decision<Input, Output>

    val step: ScheduleStep<Input, Output> get() = this::invoke

    /** Runs [action] again for as long as this schedule continues, and answers with its last output. */
    fun repeat(action: () -> Input): Output {
        var next: ScheduleStep<Input, Output> = step
        while (true) {
            when (val decision = next(action())) {
                is Continue -> {
                    decision.delay.sleepOff()
                    next = decision.step
                }

                is Done -> return decision.output
            }
        }
    }

    /** Transforms every output this schedule answers with. */
    fun <A> map(transform: (Output) -> A): Schedule<Input, A> = Schedule { step(it).map(transform) }

    /** Replaces each delay with [transform] of the output and the delay it would have been. */
    fun delayed(transform: (Output, Duration) -> Duration): Schedule<Input, Output> =
        Schedule { step(it).delayed(transform) }

    /** Scales each delay by a random factor between [min] and [max], so retries do not arrive in step. */
    fun jittered(
        min: Double = 0.0,
        max: Double = 1.0,
        random: Random = Random.Default,
    ): Schedule<Input, Output> = delayed { _, delay -> delay * random.nextDouble(min, max) }

    /** Folds every output into a running value of [B], which becomes this schedule's output. */
    fun <B> fold(initial: B, combine: (B, Output) -> B): Schedule<Input, B> {
        fun loop(input: Input, carried: B, self: ScheduleStep<Input, Output>): Decision<Input, B> =
            when (val decision = self(input)) {
                is Continue -> combine(carried, decision.output).let { folded ->
                    Continue(folded, decision.delay) { loop(it, folded, decision.step) }
                }

                is Done -> Done(carried)
            }

        return Schedule { loop(it, initial, step) }
    }

    /** Every output so far, as the schedule's output. */
    fun collect(): Schedule<Input, List<Output>> = fold(emptyList()) { carried, output -> carried + output }

    /** Continues only while [predicate] holds of the input and the output decided for it. */
    fun doWhile(predicate: (@UnsafeVariance Input, Output) -> Boolean): Schedule<Input, Output> {
        fun loop(input: Input, self: ScheduleStep<Input, Output>): Decision<Input, Output> =
            when (val decision = self(input)) {
                is Continue ->
                    if (predicate(input, decision.output)) {
                        Continue(decision.output, decision.delay) { loop(it, decision.step) }
                    } else {
                        Done(decision.output)
                    }

                is Done -> decision
            }

        return Schedule { loop(it, step) }
    }

    /** Continues until [predicate] holds of the input and the output decided for it. */
    fun doUntil(predicate: (@UnsafeVariance Input, Output) -> Boolean): Schedule<Input, Output> =
        doWhile { input, output -> !predicate(input, output) }

    /** Runs this schedule to its end and then [other], each output on the side it came from. */
    infix fun <A> andThen(other: Schedule<@UnsafeVariance Input, A>): Schedule<Input, Either<Output, A>> =
        andThen(other, { it.left() }) { it.right() }

    /** The same, with each side's output carried into a [B] of the caller's choosing. */
    fun <A, B> andThen(
        other: Schedule<@UnsafeVariance Input, A>,
        ifFirst: (Output) -> B,
        ifSecond: (A) -> B,
    ): Schedule<Input, B> = Schedule { step(it).andThen(other.step, ifFirst, ifSecond) }

    /** Continues while both schedules do, taking the longer delay and pairing the outputs. */
    infix fun <B> and(other: Schedule<@UnsafeVariance Input, B>): Schedule<Input, Pair<Output, B>> =
        and(other, ::Pair)

    /** The same, with the outputs combined by [transform]. */
    fun <B, C> and(
        other: Schedule<@UnsafeVariance Input, B>,
        transform: (Output, B) -> C,
    ): Schedule<Input, C> = and(other, transform) { left, right -> maxOf(left, right) }

    /** The same again, with the delays combined by [combineDelay] rather than by taking the longer. */
    fun <B, C> and(
        other: Schedule<@UnsafeVariance Input, B>,
        transform: (Output, B) -> C,
        combineDelay: (Duration, Duration) -> Duration,
    ): Schedule<Input, C> = Schedule { step(it).and(other.step(it), transform, combineDelay) }

    /** Continues while either schedule does, the output of the one that has stopped answering as null. */
    fun <B, C> or(
        other: Schedule<@UnsafeVariance Input, B>,
        transform: (Output?, B?) -> C,
        combineDelay: (Duration?, Duration?) -> Duration,
    ): Schedule<Input, C> = Schedule { step(it).or(other.step(it), transform, combineDelay) }

    /** Combines this schedule's output with [other]'s, and stops as soon as either of them does. */
    infix fun <B> zipLeft(other: Schedule<@UnsafeVariance Input, B>): Schedule<Input, Output> =
        and(other) { output, _ -> output }

    /** The same, answering with [other]'s output rather than this one's. */
    infix fun <B> zipRight(other: Schedule<@UnsafeVariance Input, B>): Schedule<Input, B> =
        and(other) { _, output -> output }

    companion object {

        /** Answers the input unchanged, forever. */
        fun <Input> identity(): Schedule<Input, Input> {
            fun loop(input: Input): Decision<Input, Input> = Continue(input, ZERO) { loop(it) }

            return Schedule { loop(it) }
        }

        /** Continues forever, counting the attempts. */
        fun <Input> forever(): Schedule<Input, Long> {
            fun loop(count: Long): Decision<Input, Long> = Continue(count, ZERO) { loop(count + 1) }

            return Schedule { loop(0L) }
        }

        /** Continues [n] more times after the first, counting them, and is done at the [n]th. */
        fun <Input> recurs(n: Long): Schedule<Input, Long> {
            fun loop(count: Long): Decision<Input, Long> =
                if (count < n) Continue(count, ZERO) { loop(count + 1) } else Done(count)

            return Schedule { loop(0L) }
        }

        /** Waits [duration] between attempts, forever, counting them. */
        fun <Input> spaced(duration: Duration): Schedule<Input, Long> {
            fun loop(count: Long): Decision<Input, Long> = Continue(count, duration) { loop(count + 1) }

            return Schedule { loop(0L) }
        }

        /**
         * Continues with no delay of its own until [duration] has passed on the inherited clock, answering the
         * time elapsed. It counts from its first step, which in a retry is the first failure, not the first try.
         */
        fun <Input> upTo(duration: Duration): Schedule<Input, Duration> = Schedule {
            val time = clock.get()
            val start = time.now()

            fun loop(): Decision<Input, Duration> {
                val elapsed = java.time.Duration.between(start, time.now()).toKotlinDuration()
                return if (elapsed < duration) Continue(elapsed, ZERO) { loop() } else Done(elapsed)
            }

            loop()
        }

        /** Waits [base] and then [factor] as long again each time. */
        fun <Input> exponential(base: Duration, factor: Double = 2.0): Schedule<Input, Duration> {
            fun loop(count: Int): Decision<Input, Duration> =
                (base * factor.pow(count)).let { delay -> Continue(delay, delay) { loop(count + 1) } }

            return Schedule { loop(0) }
        }

        /** Waits [base], then twice [base], then three times, and so on. */
        fun <Input> linear(base: Duration): Schedule<Input, Duration> {
            fun loop(count: Int): Decision<Input, Duration> =
                (base * count).let { delay -> Continue(delay, delay) { loop(count + 1) } }

            return Schedule { loop(1) }
        }

        /** Waits [one], then [one] again, then the sum of the two waits before it. */
        fun <Input> fibonacci(one: Duration): Schedule<Input, Duration> {
            fun loop(previous: Duration, current: Duration): Decision<Input, Duration> =
                Continue(current, current) { loop(current, previous + current) }

            return Schedule { loop(0.nanoseconds, one) }
        }

        /** Answers the input unchanged for as long as [predicate] holds of it. */
        fun <Input> doWhile(predicate: (Input, Input) -> Boolean): Schedule<Input, Input> =
            identity<Input>().doWhile(predicate)

        /** Answers the input unchanged until [predicate] holds of it. */
        fun <Input> doUntil(predicate: (Input, Input) -> Boolean): Schedule<Input, Input> =
            identity<Input>().doUntil(predicate)

        /** Answers with every input so far. */
        fun <Input> collect(): Schedule<Input, List<Input>> = identity<Input>().collect()
    }

    /** What a schedule decided about one input. */
    sealed interface Decision<in Input, out Output> {

        val output: Output

        /** The schedule has stopped, and this is what it answers with. */
        data class Done<out Output>(override val output: Output) : Decision<Any?, Output>

        /** The schedule carries on after [delay], from [step]. */
        data class Continue<in Input, out Output>(
            override val output: Output,
            val delay: Duration,
            val step: ScheduleStep<Input, Output>,
        ) : Decision<Input, Output>

        fun <A> map(transform: (Output) -> A): Decision<Input, A> = when (this) {
            is Done -> Done(transform(output))
            is Continue -> Continue(transform(output), delay) { step(it).map(transform) }
        }

        // Carried into the continuation, as `map` is: arrow-fx leaves it out, so its `jittered` scales the
        // first delay and none after it, which is not a jitter.
        fun delayed(transform: (Output, Duration) -> Duration): Decision<Input, Output> = when (this) {
            is Done -> this
            is Continue -> Continue(output, transform(output, delay)) { step(it).delayed(transform) }
        }

        fun andThen(
            other: ScheduleStep<@UnsafeVariance Input, @UnsafeVariance Output>,
        ): Decision<Input, Output> = when (this) {
            is Done -> Continue(output, ZERO, other)
            is Continue -> Continue(output, delay) { step(it).andThen(other) }
        }

        fun <A, B> andThen(
            other: ScheduleStep<@UnsafeVariance Input, A>,
            ifFirst: (Output) -> B,
            ifSecond: (A) -> B,
        ): Decision<Input, B> = map(ifFirst).andThen { other(it).map(ifSecond) }

        fun <B, C> and(
            other: Decision<@UnsafeVariance Input, B>,
            transform: (Output, B) -> C,
            combineDelay: (Duration, Duration) -> Duration,
        ): Decision<Input, C> =
            if (this is Continue && other is Continue) {
                Continue(transform(output, other.output), combineDelay(delay, other.delay)) {
                    step(it).and(other.step(it), transform, combineDelay)
                }
            } else {
                Done(transform(output, other.output))
            }

        fun <B, C> or(
            other: Decision<@UnsafeVariance Input, B>,
            transform: (Output?, B?) -> C,
            combineDelay: (Duration?, Duration?) -> Duration,
        ): Decision<Input, C> = when {
            this is Continue && other is Continue ->
                Continue(transform(output, other.output), combineDelay(delay, other.delay)) {
                    step(it).or(other.step(it), transform, combineDelay)
                }

            this is Continue -> map { transform(it, null) }

            other is Continue -> other.map { transform(null, it) }

            else -> Done(transform(output, other.output))
        }
    }
}

/** A delay, waited out on the calling virtual thread, by whichever clock that thread inherited. */
internal fun Duration.sleepOff() {
    if (this > ZERO) clock.get().sleep(this)
}
