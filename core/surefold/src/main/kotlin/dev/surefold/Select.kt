package dev.surefold

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Waits on every clause [clauses] names at once and runs the first to become ready (spec 0038): a value received, a
 * value sent, or a fork ended. Clauses ready when it is called are tried in the order written, so the first of them
 * wins a tie. Every clause that loses is unregistered before the winner's block runs.
 *
 * Interrupted before a clause wins, it throws [InterruptedException]; won first, the interrupt is kept for the caller.
 */
fun <R> select(clauses: Select<R>.() -> Unit): R {
    val written = Select<R>().apply(clauses).clauses
    require(written.isNotEmpty()) { "a select needs a clause to wait on" }
    val selection = Selection()
    val answer = try {
        entered(written, selection)
            ?: selection.await { written.forEachIndexed { at, clause -> clause.watch(selection, at) } }
    } finally {
        written.forEach { it.leave(selection) }
    }
    if (answer === Interrupted) throw InterruptedException("interrupted while selecting")
    return written[selection.won()].run(answer)
}

/** The answer of the first clause ready as it registers, or null once every clause waits or another has won. */
private fun <R> entered(clauses: List<Clause<R>>, selection: Selection): Any? {
    clauses.forEachIndexed { at, clause ->
        when (val now = clause.enter(selection, at)) {
            Pending -> {}
            Lost -> return null
            else -> return now
        }
    }
    return null
}

/** The clauses of one [select]. */
class Select<R> internal constructor() {
    internal val clauses = mutableListOf<Clause<R>>()

    /** The next value from this channel, or null once it is closed and drained, or cancelled. */
    fun <A : Any> Channel<A>.onReceive(block: (A?) -> R) {
        clauses +=
            Clause(enter = this::enterReceive, leave = this::leaveSelection) { answer -> block(received(answer)) }
    }

    /** [value] handed on: the block is told true once it is taken, or false if the consumer cancelled. */
    fun <A : Any> Channel<A>.onSend(value: A, block: (Boolean) -> R) {
        val enter = { selection: Selection, at: Int -> enterSend(value, selection, at) }
        clauses += Clause(enter = enter, leave = this::leaveSelection) { answer -> block(sent(answer)) }
    }

    /**
     * The fork's answer, as [Deferred.await] gives it. Only a fork `async` opened can be selected on: nothing else
     * says when it ends.
     */
    fun <T> Deferred<T>.onAwait(block: (T) -> R) {
        val fork = requireNotNull(this as? Fork<*, T>) { "only a fork opened by async can be selected on" }
        clauses += Clause(
            enter = { selection, at -> if (fork.wakesMe()) Pending else ended(selection, at) },
            leave = { fork.forgetMe() },
            watch = { selection, at -> if (fork.hasEnded() && ended(selection, at) !== Lost) selection.deliver(Ended) },
        ) { block(fork.await()) }
    }

    private fun ended(selection: Selection, at: Int): Any = if (selection.claim(at)) Ended else Lost
}

/** One way a [select] can end: registered by [enter], answered by what it waits on, and run once it wins. */
internal class Clause<R>(
    val enter: (Selection, Int) -> Any,
    val leave: (Selection) -> Unit,
    val watch: (Selection, Int) -> Unit = { _, _ -> },
    val run: (Any) -> R,
)

/**
 * The threads waiting on a channel or a fork are each a selection: a plain `send` or `receive` is one with a single
 * clause. Whoever answers it first claims it, so a select waiting in several places is answered by only one.
 */
internal class Selection {
    val thread: Thread = Thread.currentThread()
    private val order = ORDER.incrementAndGet()
    private val lock = ReentrantLock()
    private var winner = OPEN

    @Volatile
    private var answer: Any = Unanswered

    fun won(): Int = lock.withLock { winner }

    /** Whether [clause] won, false if something else had. */
    fun claim(clause: Int): Boolean = lock.withLock {
        (winner == OPEN).also { open -> if (open) winner = clause }
    }

    /**
     * Whether this selection's [clause] and [theirs]'s [theirClause] both won, claimed together so neither is won
     * without the other. Their locks are taken in the order the selections were made, so two selections claiming each
     * other cannot deadlock.
     */
    fun claimWith(clause: Int, theirs: Selection, theirClause: Int): Claim {
        val (first, second) = if (order < theirs.order) this to theirs else theirs to this
        return first.lock.withLock {
            second.lock.withLock {
                when {
                    winner != OPEN -> Claim.OursGone

                    theirs.winner != OPEN -> Claim.TheirsGone

                    else -> {
                        winner = clause
                        theirs.winner = theirClause
                        Claim.Both
                    }
                }
            }
        }
    }

    /** The answer for whichever clause was claimed, waking the selecting thread. */
    fun deliver(with: Any) {
        answer = with
        Waiting.unpark(thread)
    }

    /**
     * Parks until answered, calling [watch] on each wake. Interrupted first, it claims itself and answers [Interrupted]
     * for the caller to throw; answered first, the interrupt is kept.
     */
    fun await(watch: () -> Unit): Any {
        var interrupted = false
        while (answer === Unanswered) {
            watch()
            if (answer !== Unanswered) break
            Waiting.park(this)
            if (Thread.interrupted()) {
                if (claim(INTERRUPTED)) return Interrupted
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        return answer
    }
}

/** How two selections claimed together came out. */
internal enum class Claim { Both, OursGone, TheirsGone }

private val ORDER = AtomicLong()
private const val OPEN = -1
private const val INTERRUPTED = -2

/** A clause registered and waiting. */
internal object Pending

/** A selection something else has already won. */
internal object Lost

/** A selection interrupted before anything won it. */
internal object Interrupted

private object Unanswered

/** A fork that ended. */
private object Ended
