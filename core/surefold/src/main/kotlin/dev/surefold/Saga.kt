package dev.surefold

import dev.surefold.fail.Fail
import dev.surefold.fail.Failed

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
     * [undo] is handed the key `"$id/$name/undo"`, that value and why. A declared fail means the action did not
     * happen; anything else it throws leaves it unknown, and [check], asked with the same key, settles it. Without
     * an answer the saga is unsettled, and undoes nothing.
     */
    fun <A> step(
        name: String,
        action: (key: String) -> A,
        check: ((key: String) -> Settled<A>)? = null,
        undo: (key: String, value: A, why: Undo<E>) -> Unit,
    ): A
}

/**
 * A saga whose undos did not all succeed: each step named in [notUndone] may still be done. When [unsettled] names
 * a step whose outcome nobody could settle, no undo ran at all.
 */
class NotUndone(
    val saga: String,
    val notUndone: List<String>,
    val why: Undo<*>,
    val unsettled: List<String> = emptyList(),
) : RuntimeException("saga $saga left ${notUndone.joinToString()} done after $why", causeOf(why))

/** Runs [block] as the saga [id], undoing its finished steps, newest first, if it ends any way but returning. */
fun <A> saga(id: String, block: SagaScope<Nothing>.() -> A): A = Unraisable.saga(id, block)

/**
 * Runs [block] as the saga [id], undoing its finished steps, newest first, if it ends any way but returning; the
 * ending then carries on, unless an undo failed, when the saga throws [NotUndone] instead.
 */
fun <E, A> Fail<E>.saga(id: String, block: SagaScope<E>.() -> A): A {
    val steps = Steps<E>(id, root())
    return try {
        steps.block()
    } catch (t: Throwable) {
        throw steps.undoAll(steps.why(t), t)
    }
}

private fun causeOf(why: Undo<*>): Throwable? = when (why) {
    is Undo.Failed -> null
    is Undo.Threw -> why.failure
    is Undo.Cancelled -> why.interrupt
}

private class Done<E, A>(val name: String, val value: A, val undo: (String, A, Undo<E>) -> Unit)

/** A step nobody could settle, and the check's own throw if it had one. */
private class Unsettled(val name: String, val checkFailed: Throwable?)

private class Steps<E>(override val id: String, private val root: Fail<*>) : SagaScope<E> {

    private val names = mutableSetOf<String>()
    private val done = mutableListOf<Done<E, *>>()
    private val unsettled = mutableListOf<Unsettled>()

    override fun <A> step(
        name: String,
        action: (key: String) -> A,
        check: ((key: String) -> Settled<A>)?,
        undo: (String, A, Undo<E>) -> Unit,
    ): A {
        require(names.add(name)) { "saga $id already has a step named $name" }
        val key = "$id/$name"
        return try {
            action(key).also { done += Done(name, it, undo) }
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
        if (check == null) {
            unsettled += Unsettled(name, null)
            return
        }
        try {
            when (val settled = check(key)) {
                is Settled.Happened -> done += Done(name, settled.value, undo)
                Settled.DidNot -> Unit
            }
        } catch (t: Throwable) {
            unsettled += Unsettled(name, t)
        }
    }

    // Only a fail on this saga's own attempt carries an E; one into an outer attempt is, here, just a throw.
    private fun declared(t: Throwable): Boolean = t is Failed && t.scope === root

    fun why(t: Throwable): Undo<E> = when {
        declared(t) ->
            @Suppress("UNCHECKED_CAST")
            Undo.Failed((t as Failed).error as E)

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
            undo("$id/$name/undo", value, why)
            null
        } catch (t: Throwable) {
            t
        }
}
