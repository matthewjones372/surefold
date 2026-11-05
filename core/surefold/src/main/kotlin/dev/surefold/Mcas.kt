package dev.surefold

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * One word of the multi-word compare-and-swap a transaction commits with (spec 0209), after Guerraoui, Kogan, Marathe
 * and Zablotchi, "Efficient Multi-word Compare and Swap" (2020). Values are compared by identity, not `equals`.
 */
internal class Word<T>(initial: T) {
    internal val id: Long = ids.getAndIncrement()
    internal val held = AtomicReference(Installed(initial, initial, settled))

    /** The value as of the last swap to succeed. It never waits and never helps. */
    fun read(): T = held.get().value()
}

internal class Change<T>(val word: Word<T>, val expected: T, val desired: T)

/** Changes every word or none: each word must still hold its expected value, or nothing is written. */
internal fun swapAll(changes: List<Change<*>>): Boolean {
    require(changes.distinctBy { it.word.id }.size == changes.size) { "A swap names each word once." }
    return Swap(changes).run()
}

private val ids = AtomicLong()

private enum class Status {
    UNDECIDED,
    SUCCEEDED,
    FAILED,
}

// A word under an undecided or failed swap reads as before, and under one that succeeded as after.
internal class Installed<T>(private val before: T, private val after: T, val swap: Swap) {
    fun value(): T = if (swap.succeeded()) after else before
}

private class Planned<T>(val change: Change<T>, val held: Installed<T>)

private fun <T> planned(change: Change<T>, swap: Swap) =
    Planned(change, Installed(change.expected, change.desired, swap))

internal class Swap(changes: List<Change<*>>) {
    private val status = AtomicReference(Status.UNDECIDED)

    // Words are taken in id order, so two swaps helping each other never wait on one another in a cycle.
    private val entries: List<Planned<*>> = changes.sortedBy { it.word.id }.map { planned(it, this) }

    fun succeeded(): Boolean = status.get() == Status.SUCCEEDED

    fun undecided(): Boolean = status.get() == Status.UNDECIDED

    /** Run by the swap's owner and by any swap that finds it half-installed; whoever decides first decides. */
    fun run(): Boolean {
        val outcome = if (entries.all { install(it) }) Status.SUCCEEDED else Status.FAILED
        status.compareAndSet(Status.UNDECIDED, outcome)
        return succeeded()
    }

    private fun <T> install(entry: Planned<T>): Boolean {
        val word = entry.change.word
        while (true) {
            val seen = word.held.get()
            // The word is read before the status, so a helper whose install lands after the decision is helping a swap
            // that failed: its held value reads as expected, which is what the word already held.
            val done = seen === entry.held || !undecided()
            val found = seen.swap
            when {
                done -> return true
                found.undecided() -> found.run()
                seen.value() !== entry.change.expected -> return false
                word.held.compareAndSet(seen, entry.held) -> return true
            }
        }
    }
}

// Holds the value a word is created with. Settled before any swap can see it.
private val settled = Swap(emptyList()).also { it.run() }
