package dev.surefold.junit

import dev.surefold.SurefoldLocal
import dev.surefold.surefoldLocal
import dev.surefold.test.NotHeld
import dev.surefold.test.Tries
import dev.surefold.test.TrySeam
import dev.surefold.test.tries
import java.util.concurrent.CopyOnWriteArrayList

/** Every check made while a test runs, in the order made: the test's result. Forks share their opener's. */
internal class Record {
    private val checks = CopyOnWriteArrayList<Expectations>()

    val size: Int get() = checks.size

    fun add(check: Expectations) {
        checks += check
    }

    fun addAll(record: Record) {
        checks += record.checks
    }

    /** Everything recorded, as one result. */
    fun joined(): Expectations = joined(checks)

    /** What was recorded from [from] on, as one result. */
    fun since(from: Int): Expectations = joined(checks.drop(from))

    private fun joined(some: List<Expectations>) = some.fold(nothingChecked(), Expectations::plus)
}

/** The record a check made here adds to: a test's, or a narrower one inside it. Unbound outside a test. */
internal val record: SurefoldLocal<Record?> = surefoldLocal { null }

/** [block]'s answer, and every check made while it ran, kept out of any record around it. */
@OptIn(TrySeam::class)
internal fun <A> recording(block: () -> A): Pair<A, Expectations> {
    val into = Record()
    val answer = record.locally(into) { tries.locally(KeepingHeldTries) { block() } }
    return answer to into.joined()
}

/** [check], added to the record bound here. A check made where none is, outside a test, is an error. */
internal fun recorded(check: Expectations): Expectations {
    val into = checkNotNull(record.get()) {
        "a check made outside a test, or on a thread surefold does not carry the test's context to: $check"
    }
    into.add(check)
    return check
}

/** Adds [check] to the record bound here, if there is one: a result built from checks already counted is not. */
internal fun recordedIfInTest(check: Expectations): Expectations = check.also { record.get()?.add(it) }

/**
 * Each try of an `eventually` checks into a record of its own. A try whose checks held adds them to the test's; one
 * whose checks did not throws them, with its answer, as [NotHeld], and they count only if it is the last, as given up.
 */
@OptIn(TrySeam::class)
private object KeepingHeldTries : Tries {
    override fun <A> attempt(block: () -> A): A {
        val into = Record()
        val answer = record.locally(into) { block() }
        val checks = into.joined()
        if (!checks.held) throw NotHeld(answer, checks)
        record.get()?.addAll(into)
        return answer
    }
}
