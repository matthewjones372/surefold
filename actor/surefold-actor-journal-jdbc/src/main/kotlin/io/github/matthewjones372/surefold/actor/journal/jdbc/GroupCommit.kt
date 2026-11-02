package io.github.matthewjones372.surefold.actor.journal.jdbc

import dev.surefold.fail.Either
import dev.surefold.logDebug
import io.github.matthewjones372.surefold.actor.JournalConflict
import io.github.matthewjones372.surefold.actor.PersistenceId
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * Appends that share a commit (spec 0108): up to [maxAppends] of them in one transaction, and up to [committers]
 * such transactions at once, each on its own connection.
 */
data class GroupCommit(val maxAppends: Int = 64, val committers: Int = 2) {
    init {
        require(maxAppends >= 1) { "a group holds at least one append, was $maxAppends" }
        require(committers >= 1) { "at least one committer, was $committers" }
    }
}

/** One append waiting for a group. */
internal class Append(val id: PersistenceId, val expected: Long, val events: List<ByteArray>, val metadata: String?)

/**
 * Takes whatever appends are queued and commits them together: [group] writes them all on one connection in one
 * round trip, and one commit follows. There is no timer: a lone append goes at once, and appends that arrive while a
 * commit is in flight form the next group. A committer ends when it finds nothing queued, so an idle journal holds
 * no thread. A group whose writing fails, as two writers racing for one id do, is rolled back and each of its appends
 * is made [alone], as it would have been without group commit.
 */
internal class Committer(
    private val dataSource: DataSource,
    private val settings: GroupCommit,
    private val group: (Connection, List<Append>) -> List<Result<Either<JournalConflict, Long>>>,
    private val alone: (Append) -> Either<JournalConflict, Long>,
) {
    private class Pending(val append: Append) {
        val answer = CompletableFuture<Either<JournalConflict, Long>>()
    }

    private val queue = ConcurrentLinkedQueue<Pending>()
    private val running = AtomicInteger()

    /** How many appends wait for a committer, for tests. */
    fun queued(): Int = queue.size

    fun append(append: Append): Either<JournalConflict, Long> {
        val pending = Pending(append)
        queue.add(pending)
        if (claim()) Thread.ofVirtual().name("surefold-journal-committer").start(::run)
        return try {
            pending.answer.join()
        } catch (failed: CompletionException) {
            throw failed.cause ?: failed
        }
    }

    /** A committer's place, if fewer than [GroupCommit.committers] are running. */
    private fun claim(): Boolean {
        while (true) {
            val now = running.get()
            if (now >= settings.committers) return false
            if (running.compareAndSet(now, now + 1)) return true
        }
    }

    private fun run() {
        while (true) {
            val taken = generateSequence { queue.poll() }.take(settings.maxAppends).toList()
            if (taken.isEmpty()) {
                running.decrementAndGet()
                // An append queued between the empty poll and the decrement found every place taken: it is ours.
                if (queue.isEmpty() || !claim()) return
            } else {
                commit(taken)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Whatever failed is every waiting append's to throw, never lost here.
    private fun commit(taken: List<Pending>) {
        try {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                val results = try {
                    group(connection, taken.map { it.append })
                } catch (raced: SQLException) {
                    connection.rollback()
                    logDebug("journal: a group of ${taken.size} failed as one (${raced.message}); making each alone")
                    null
                }
                if (results == null) {
                    taken.forEach { pending -> answerAlone(pending) }
                    return
                }
                try {
                    connection.commit()
                } catch (failed: SQLException) {
                    try {
                        connection.rollback()
                    } catch (_: SQLException) {
                        // The commit's failure is the one to report; the connection goes back to the pool broken.
                    }
                    throw failed
                }
                taken.zip(results).forEach { (pending, result) ->
                    result.fold({ pending.answer.complete(it) }, { pending.answer.completeExceptionally(it) })
                }
            }
        } catch (failed: Throwable) {
            // No append is answered as written unless its group committed.
            taken.forEach { it.answer.completeExceptionally(failed) }
        }
    }

    @Suppress("TooGenericExceptionCaught") // As above: the append's own failure is its caller's to throw.
    private fun answerAlone(pending: Pending) {
        try {
            pending.answer.complete(alone(pending.append))
        } catch (failed: Throwable) {
            pending.answer.completeExceptionally(failed)
        }
    }
}
