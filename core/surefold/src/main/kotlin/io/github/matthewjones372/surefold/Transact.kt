package io.github.matthewjones372.surefold

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A value forks share, changed only inside [transact] (spec 0209). [name] is what a step that lost to a change in it
 * reports as the cause of its retry (spec 0212); keep it to a bounded set, as it becomes a metric tag.
 */
class Shared<T>(initial: T, val name: String? = null) {
    internal val word = Word(Versioned(initial, 0))

    // Threads parked in waitForChange on a step that read this value. Each takes itself off when it wakes.
    internal val waiters = ConcurrentLinkedQueue<Thread>()

    /** The latest committed value, read outside any step: deciding a write on it races every other step. */
    fun peek(): T = word.read().value

    /**
     * Changes the value by [change] as a step of its own, named [name] (spec 0247). Inside a step, it is part of that
     * step, so `transact { a.update { … }; b.update { … } }` still lands both together.
     */
    fun update(change: (T) -> T) {
        transact(name) { this@Shared.set(change(this@Shared.get())) }
    }

    /** Changes the value by [change] and answers what [change] answers beside it, as one step, as [update] does. */
    fun <A> modify(change: (T) -> Pair<T, A>): A = transact(name) {
        val (next, answer) = change(this@Shared.get())
        this@Shared.set(next)
        answer
    }

    /** Parks until a committed value makes [holds] true, and answers it, as [Transaction.waitForChange] parks. */
    fun awaitUntil(holds: (T) -> Boolean): T = transact(name) {
        val value = this@Shared.get()
        if (holds(value)) value else waitForChange()
    }
}

/**
 * Runs [block] as one step: its writes land together when it commits, or not at all if it throws or fails. A step whose
 * reads were changed by another commit first runs again, so [block] can run more than once. Inside another step, it
 * joins that step. Effects queued with [Transaction.afterCommit] run once it has committed, before this returns.
 *
 * A step that lost [soloAfter] runs runs alone (spec 0231): no other step commits until it has, so it commits on that
 * run. Every other commit waits for it meanwhile, so a solo run that blocks holds them all up. `Int.MAX_VALUE` never
 * runs alone.
 */
fun <A> transact(name: String? = null, soloAfter: Int = SOLO_AFTER, block: Transaction.() -> A): A {
    require(soloAfter >= 0) { "A step cannot run alone after $soloAfter lost runs." }
    running.get()?.let { return it.block() }
    val (step, answer) = untilCommitted(name ?: UNNAMED, soloAfter, block)
    // Outside the step, so an effect that calls transact starts a step of its own; and after the run is counted
    // committed, so an effect that throws does not count it failed.
    step.runEffects()
    return answer
}

// Runs the step until a run commits, recording each run's outcome and each retry's cause (spec 0212).
private fun <A> untilCommitted(name: String, soloAfter: Int, block: Transaction.() -> A): Pair<Transaction, A> {
    var attempts = 0
    // Null until the first retry, so a step that commits on its first run allocates nothing for it.
    var causes: Map<String, Int>? = null
    try {
        while (true) {
            attempts++
            // Taken before the run starts, so every value it reads was committed before anyone else was stopped.
            val step = Transaction(if (attempts > soloAfter) Solo.take() else null)
            val answer = step.attempt(block)
            if (answer !== Again) {
                counter(RUNS, "transact" to name, "outcome" to if (step.alone) "solo" else "committed").increment()
                histogram(ATTEMPTS, "transact" to name).record(attempts.toDouble())
                causes?.let { RetryWatchers.committed(name, attempts, it, step.alone) }
                // Only Again is not the block's answer, so this is an A.
                @Suppress("UNCHECKED_CAST")
                return step to answer as A
            }
            retried.get().retried(name, step.cause)
            val counted = causes.orEmpty()
            causes = counted + (step.cause to (counted[step.cause] ?: 0) + 1)
        }
    } catch (interrupted: InterruptedException) {
        counter(RUNS, "transact" to name, "outcome" to "cancelled").increment()
        throw interrupted
    } catch (t: Throwable) {
        counter(RUNS, "transact" to name, "outcome" to "failed").increment()
        throw t
    }
}

// One run of the step: the block's answer once it has committed, or Again.
private fun <A> Transaction.attempt(block: Transaction.() -> A): Any? {
    running.set(this)
    return try {
        val answer = block()
        if (commit()) answer else Again
    } catch (_: Conflict) {
        Again
    } catch (_: Wait) {
        cause = WAITED
        // A solo run that waits gives the word up first: the commit it waits for could not land otherwise.
        giveUpSolo()
        awaitChange()
        Again
    } finally {
        giveUpSolo()
        running.remove()
    }
}

private object Again

private const val UNNAMED = "unnamed"

private const val WAITED = "waited"

private const val SOLO_AFTER = 10

/** Told why each run of a step gave up: the step's name, and the value it lost to or `waited` (spec 0212). */
internal fun interface Retried {
    fun retried(step: String, cause: String)
}

internal val retried: SurefoldLocal<Retried> = surefoldLocal {
    Retried { step, cause -> counter(RETRIES, "transact" to step, "cause" to cause).increment() }
}

private const val RUNS = "surefold.transact.runs"

private const val RETRIES = "surefold.transact.retries"

private const val ATTEMPTS = "surefold.transact.attempts"

// A thread local rather than a SurefoldLocal: a fork must not inherit the step it was started in.
private val running = ThreadLocal<Transaction?>()

/** The step a [transact] block runs in. A [Shared] is read and written only through it. */
class Transaction internal constructor(private var solo: Any?) {
    // Whether this run ran alone (spec 0231), which it still did after giving the word up.
    internal val alone = solo != null

    private var readAt: Tick = commits.read()
    private val reads = HashMap<Shared<*>, Versioned<*>>()
    private val writes = HashMap<Shared<*>, Any?>()
    private val effects = ArrayList<() -> Unit>()

    // Why this run gave up: the name of the first value it read that changed, or waited.
    internal var cause: String = UNNAMED

    fun <T> Shared<T>.get(): T {
        // Only a set on this same Shared put it there, so it is a T.
        @Suppress("UNCHECKED_CAST")
        if (this in writes) return writes[this] as T
        @Suppress("UNCHECKED_CAST")
        val seen = reads.getOrPut(this) { consistent(this) } as Versioned<T>
        return seen.value
    }

    fun <T> Shared<T>.set(value: T) {
        writes[this] = value
    }

    /**
     * Runs [effect] once the step has committed, in the order effects were queued (spec 0211). A run that retries,
     * waits, throws or fails drops its effects, so each runs once per commit. An effect that throws does not undo the
     * commit.
     */
    fun afterCommit(effect: () -> Unit) {
        effects.add(effect)
    }

    /** Drops this run and parks until a value it read is committed again, then runs the step again. */
    fun waitForChange(): Nothing {
        check(reads.isNotEmpty()) { "waitForChange() in a step that has read nothing would wait forever." }
        throw Wait
    }

    /** The first branch that does not wait. When every branch waits, the step waits on what any of them read. */
    fun <A> firstOf(vararg branches: Transaction.() -> A): A {
        for (branch in branches) {
            val before = HashMap(writes)
            val queued = effects.size
            try {
                return branch()
            } catch (_: Wait) {
                writes.clear()
                writes.putAll(before)
                effects.subList(queued, effects.size).clear()
            }
        }
        waitForChange()
    }

    // Every effect runs even if one before it threw: the commit has happened, and each effect is owed to it.
    internal fun runEffects() {
        var thrown: Throwable? = null
        for (effect in effects) {
            try {
                effect()
            } catch (t: Throwable) {
                thrown?.addSuppressed(t) ?: run { thrown = t }
            }
        }
        thrown?.let { throw it }
    }

    // Registered before the check, and a commit wakes after it lands, so a commit is either seen here or wakes us.
    internal fun awaitChange() {
        val me = Thread.currentThread()
        reads.keys.forEach { it.waiters.add(me) }
        try {
            while (!changed()) {
                Waiting.park(this)
                if (Thread.interrupted()) throw InterruptedException()
            }
        } finally {
            reads.keys.forEach { it.waiters.remove(me) }
        }
    }

    internal fun giveUpSolo() {
        solo?.let(Solo::give)
        solo = null
    }

    private fun changed() = lostTo() != null

    private fun lostTo(): Shared<*>? = reads.entries.firstOrNull { (read, held) -> read.word.read() !== held }?.key

    private fun lose(to: Shared<*>) {
        cause = to.name ?: UNNAMED
    }

    // A value written after readAt is taken only once every earlier read is shown to hold at a later count, so a body
    // never sees values that were never current together.
    private fun <T> consistent(shared: Shared<T>): Versioned<T> {
        while (true) {
            val seen = shared.word.read()
            if (seen.version <= readAt.n) return seen
            val now = commits.read()
            lostTo()?.let { lose(it); throw Conflict }
            readAt = now
        }
    }

    // The commit count is one of the swapped words, so a commit's writes and its version land together. Words only
    // read are swapped for themselves, which fails the commit if any changed since they were read. The solo word is
    // swapped too: expected free, so no commit lands while a step runs alone, or held by this run, which frees it.
    internal fun commit(): Boolean {
        if (writes.isEmpty()) return true
        while (true) {
            val now = commits.read()
            val next = Tick(now.n + 1)
            val unwritten = reads.filterKeys { it !in writes }.map { (read, held) -> unchanged(read, held) }
            val written = writes.map { (shared, value) -> written(shared, reads[shared], value, next.n) }
            val alone = Change(Solo.word, solo ?: Free, Free)
            if (swapAll(listOf(Change(commits, now, next), alone) + unwritten + written)) {
                writes.keys.forEach { shared -> shared.waiters.forEach(Waiting::unpark) }
                return true
            }
            lostTo()?.let { lose(it); return false }
            // Lost to nothing it read, so another step runs alone: try again once that step is done.
            if (solo == null) Solo.awaitFree()
        }
    }
}

// Values are fresh instances, never equal by identity to one another, which the swap's identity comparison relies on.
internal class Versioned<T>(val value: T, val version: Long)

internal class Tick(val n: Long)

private val commits = Word(Tick(0))

/** One step at a time may run alone (spec 0231), holding [word] with a token of its own until it is done. */
private object Solo {
    val word = Word<Any>(Free)

    // Threads parked until the step running alone is done. Each takes itself off when it wakes.
    private val waiters = ConcurrentLinkedQueue<Thread>()

    fun take(): Any {
        val mine = Any()
        while (!swapAll(listOf(Change(word, Free, mine)))) awaitFree()
        return mine
    }

    // A run that committed writes has freed the word already; this then frees nothing, and only wakes.
    fun give(mine: Any) {
        swapAll(listOf(Change(word, mine, Free)))
        waiters.forEach(Waiting::unpark)
    }

    // Registered before the check, and give wakes after the word is free, so a give is either seen here or wakes us.
    fun awaitFree() {
        val me = Thread.currentThread()
        waiters.add(me)
        try {
            while (word.read() !== Free) {
                Waiting.park(this)
                if (Thread.interrupted()) throw InterruptedException()
            }
        } finally {
            waiters.remove(me)
        }
    }
}

private object Free

private object Conflict : Throwable(null, null, false, false)

private object Wait : Throwable(null, null, false, false)

@Suppress("UNCHECKED_CAST")
private fun <T> unchanged(shared: Shared<T>, held: Versioned<*>): Change<*> =
    Change(shared.word, held as Versioned<T>, held)

// A write to a Shared the step never read expects whatever is there now.
@Suppress("UNCHECKED_CAST")
private fun <T> written(shared: Shared<T>, read: Versioned<*>?, value: Any?, version: Long): Change<*> =
    Change(shared.word, (read ?: shared.word.read()) as Versioned<T>, Versioned(value as T, version))
