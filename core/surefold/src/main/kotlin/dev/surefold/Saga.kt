package dev.surefold

import dev.surefold.fail.Fail
import dev.surefold.fail.Failed
import java.time.Instant
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/** Why a saga's undos are running, as each undo is told it (spec 0298). */
sealed class Undo<out E> {
    /** The saga's block failed with a declared [error]. */
    data class Failed<out E>(val error: E) : Undo<E>()

    /** The block threw something nobody declared. */
    data class Threw(val failure: Throwable) : Undo<Nothing>()

    /** The block was interrupted. */
    data class Cancelled(val interrupt: InterruptedException) : Undo<Nothing>()
}

/** What a step's check found out about an action whose outcome was unknown. */
sealed class Settled<out A> {
    /** The action did happen, and returned [value]: the step is done, and its undo runs. */
    data class Happened<out A>(val value: A) : Settled<A>()

    /** The action did not happen: there is nothing to undo. */
    data object DidNot : Settled<Nothing>()
}

/** The capability of taking a step that a later failure in the same saga undoes. */
interface SagaScope<E> {
    /** The saga's id, which every step's key starts with. */
    val id: String

    /**
     * Runs [action] with the key `"$id/$name"` and records [undo] against what it returned. Should the saga fail,
     * [undo] is handed the key `"$id/$name/undo"`, that value and why. A declared fail means the action did not happen;
     * anything else it throws leaves it unknown, and [check], asked with the same key, settles it. Without an answer
     * the saga is unsettled, and undoes nothing.
     */
    fun <A> step(
        name: String,
        action: (key: String) -> A,
        check: ((key: String) -> Settled<A>)? = null,
        undo: (key: String, value: A, why: Undo<E>) -> Unit,
    ): A
}

/**
 * A saga whose undos did not all succeed: each step named in [notUndone] may still be done. When [unsettled] names a
 * step whose outcome nobody could settle, no undo ran at all.
 */
class NotUndone(
    val saga: String,
    val notUndone: List<String>,
    val why: Undo<*>,
    val unsettled: List<String> = emptyList(),
) : RuntimeException("saga $saga left ${notUndone.joinToString()} done after $why", causeOf(why))

/** Runs [block] as the saga [id], undoing its finished steps, newest first, if it ends any way but returning. */
fun <A> saga(id: String, name: String = "unnamed", undo: Policy? = null, block: SagaScope<Nothing>.() -> A): A =
    Unraisable.saga(id, name, undo, block)

/**
 * Runs [block] as the saga [id], undoing its finished steps, newest first, if it ends any way but returning; the ending
 * then carries on, unless an undo failed, when the saga throws [NotUndone] instead. Undos and checks run on a virtual
 * thread of their own, which an interrupt of this one does not reach, each through [undo]; its deadline, if it has one,
 * bounds all of them together. Steps may be taken from `parZip` and `parMap` branches; undos then run in the reverse of
 * the order their actions returned. [name], not [id], tags the saga's metrics.
 */
fun <E, A> Fail<E>.saga(id: String, name: String = "unnamed", undo: Policy? = null, block: SagaScope<E>.() -> A): A {
    val steps = Steps<E>(id, name, root(), Undoing(undo))
    val value =
        try {
            steps.block()
        } catch (t: Throwable) {
            val ending = steps.undoAll(steps.why(t), t)
            runs(name, if (ending is NotUndone) "not-undone" else "undone")
            throw ending
        }
    runs(name, "done")
    return value
}

private fun runs(saga: String, outcome: String) =
    counter("surefold.saga.runs", "saga" to saga, "outcome" to outcome).increment()

private fun causeOf(why: Undo<*>): Throwable? =
    when (why) {
        is Undo.Failed -> null
        is Undo.Threw -> why.failure
        is Undo.Cancelled -> why.interrupt
    }

private class Done<E, A>(val name: String, val value: A, val undo: (String, A, Undo<E>) -> Unit)

/** A step nobody could settle, and the check's own throw if it had one. */
private class Unsettled(val name: String, val checkFailed: Throwable?)

/** How a saga's undos and checks are run: shielded, each through the policy, all inside its deadline. */
private class Undoing(policy: Policy?) {
    private val each = policy?.let { Policy(it.name, it.steps.filterNot { step -> step is Policy.Step.Deadline }) }
    private val total = policy?.steps?.filterIsInstance<Policy.Step.Deadline>()?.minOfOrNull { it.total }
    private var until: Instant? = null

    fun <A> run(call: () -> A): A = shielded {
        val left = left()
        when {
            left == null -> through(call)
            left.isPositive() -> timeout(left) { through(call) }
            else -> throw TimeoutException("the undo policy's deadline of $total has passed")
        }
    }

    private fun <A> through(call: () -> A): A = each?.invoke(call) ?: call()

    // The deadline starts with the first undo or check, not with the saga.
    private fun left(): Duration? = total?.let { deadline ->
        val end = until ?: clock.get().now().plus(deadline.toJavaDuration()).also { until = it }
        java.time.Duration.between(clock.get().now(), end).toKotlinDuration()
    }
}

/** [block] on a virtual thread of its own carrying this thread's locals, waited for whatever interrupts this one. */
private fun <A> shielded(block: () -> A): A {
    val bound = Bindings.snapshot()
    val ended = ShieldedEnd<A>()
    Thread.ofVirtual().name("surefold-saga-shielded").start { ended.from { Bindings.under(bound, block) } }.joinFully()
    return ended.answer()
}

private class ShieldedEnd<A> {
    private var value: A? = null
    private var thrown: Throwable? = null

    fun from(block: () -> A) {
        try {
            value = block()
        } catch (t: Throwable) {
            thrown = t
        }
    }

    // Read only after the thread that wrote it was joined.
    @Suppress("UNCHECKED_CAST") fun answer(): A = thrown?.let { throw it } ?: value as A
}

private class Steps<E>(
    override val id: String,
    private val saga: String,
    private val root: Fail<*>,
    private val undoing: Undoing,
) : SagaScope<E> {

    // Steps may be taken from several forks at once; the undos are read once every fork has ended.
    private val lock = Any()
    private val names = mutableSetOf<String>()
    private val done = mutableListOf<Done<E, *>>()
    private val unsettled = mutableListOf<Unsettled>()

    private fun record(step: Done<E, *>) = synchronized(lock) { done += step }

    private fun record(step: Unsettled) = synchronized(lock) { unsettled += step }

    override fun <A> step(
        name: String,
        action: (key: String) -> A,
        check: ((key: String) -> Settled<A>)?,
        undo: (String, A, Undo<E>) -> Unit,
    ): A {
        require(synchronized(lock) { names.add(name) }) { "saga $id already has a step named $name" }
        val key = "$id/$name"
        return try {
            action(key).also { record(Done(name, it, undo)) }
        } catch (t: Throwable) {
            if (!declared(t)) settle(name, key, check, undo)
            throw t
        }
    }

    private fun <A> settle(
        name: String,
        key: String,
        check: ((String) -> Settled<A>)?,
        undo: (String, A, Undo<E>) -> Unit,
    ) {
        val answer =
            if (check == null) {
                record(Unsettled(name, null))
                "none"
            } else {
                try {
                    when (val settled = undoing.run { check(key) }) {
                        is Settled.Happened -> "happened".also { record(Done(name, settled.value, undo)) }
                        Settled.DidNot -> "did-not"
                    }
                } catch (t: Throwable) {
                    "none".also { record(Unsettled(name, t)) }
                }
            }
        counter("surefold.saga.checks", "saga" to saga, "answer" to answer).increment()
    }

    // A fail is declared whichever attempt it leaves through, a fork's own included: the step did not happen.
    private fun declared(t: Throwable): Boolean = t is Failed

    fun why(t: Throwable): Undo<E> =
        when {
            // Only a fail on this saga's own attempt carries an E; one into an outer attempt is, here, just a throw.
            t is Failed && t.scope === root -> @Suppress("UNCHECKED_CAST") Undo.Failed(t.error as E)

            t is InterruptedException -> Undo.Cancelled(t)

            else -> Undo.Threw(t)
        }

    /**
     * Every undo runs, newest first, whatever the ones before it did. With a step unsettled none does: undoing the
     * steps before one that may have happened could pay twice.
     */
    fun undoAll(why: Undo<E>, ending: Throwable): Throwable {
        if (unsettled.isNotEmpty()) {
            val names = unsettled.map { it.name }
            return NotUndone(id, names + done.asReversed().map { it.name }, why, names).apply {
                this@Steps.unsettled.mapNotNull { it.checkFailed }.forEach { addSuppressed(it) }
            }
        }
        val failed = done.asReversed().mapNotNull { step -> step.undone(why)?.let { step.name to it } }
        if (failed.isEmpty()) return ending
        return NotUndone(id, failed.map { it.first }, why).apply { failed.forEach { addSuppressed(it.second) } }
    }

    private fun <A> Done<E, A>.undone(why: Undo<E>): Throwable? =
        try {
            undoing.run { undo("$id/$name/undo", value, why) }
            null
        } catch (t: Throwable) {
            t
        }
}
