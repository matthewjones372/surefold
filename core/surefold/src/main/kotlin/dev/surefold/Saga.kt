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

/** The capability of taking a step that a later failure in the same saga undoes. */
interface SagaScope<E> {
    /** The saga's id, which every step's key starts with. */
    val id: String

    /**
     * Runs [action] with the key `"$id/$name"` and records [undo] against what it returned. Should the saga fail,
     * [undo] is handed the key `"$id/$name/undo"`, that value and why.
     */
    fun <A> step(name: String, action: (key: String) -> A, undo: (key: String, value: A, why: Undo<E>) -> Unit): A
}

/** A saga whose undos did not all succeed: each step named in [notUndone] may still be done. */
class NotUndone(val saga: String, val notUndone: List<String>, val why: Undo<*>) :
    RuntimeException("saga $saga left ${notUndone.joinToString()} done after $why", causeOf(why))

/** Runs [block] as the saga [id], undoing its finished steps, newest first, if it ends any way but returning. */
fun <A> saga(id: String, block: SagaScope<Nothing>.() -> A): A = Unraisable.saga(id, block)

/**
 * Runs [block] as the saga [id], undoing its finished steps, newest first, if it ends any way but returning; the
 * ending then carries on, unless an undo failed, when the saga throws [NotUndone] instead.
 */
fun <E, A> Fail<E>.saga(id: String, block: SagaScope<E>.() -> A): A {
    val steps = Steps<E>(id)
    return try {
        steps.block()
    } catch (t: Throwable) {
        throw steps.undoAll(why(t), t)
    }
}

private fun <E> Fail<E>.why(t: Throwable): Undo<E> = when {
    // Only a fail on this saga's own attempt carries an E; one into an outer attempt is, here, just a throw.
    t is Failed && t.scope === root() ->
        @Suppress("UNCHECKED_CAST")
        Undo.Failed(t.error as E)

    t is InterruptedException -> Undo.Cancelled(t)

    else -> Undo.Threw(t)
}

private fun causeOf(why: Undo<*>): Throwable? = when (why) {
    is Undo.Failed -> null
    is Undo.Threw -> why.failure
    is Undo.Cancelled -> why.interrupt
}

private class Done<E, A>(val name: String, val value: A, val undo: (String, A, Undo<E>) -> Unit)

private class Steps<E>(override val id: String) : SagaScope<E> {

    private val done = mutableListOf<Done<E, *>>()

    override fun <A> step(name: String, action: (key: String) -> A, undo: (String, A, Undo<E>) -> Unit): A {
        require(done.none { it.name == name }) { "saga $id already has a step named $name" }
        return action("$id/$name").also { done += Done(name, it, undo) }
    }

    /** Every undo runs, newest first, whatever the ones before it did. */
    fun undoAll(why: Undo<E>, ending: Throwable): Throwable {
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
