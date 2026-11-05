package dev.surefold.actor.journal.jdbc

import dev.surefold.Promise
import dev.surefold.Shared
import dev.surefold.actor.JournalConflict
import dev.surefold.actor.PersistenceId
import dev.surefold.fail.Either
import dev.surefold.fail.fold
import dev.surefold.logDebug
import dev.surefold.transact
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sql.DataSource

/**
 * Appends that share a commit (spec 0108): up to [maxAppends] of them in one transaction, and up to [committers] such
 * transactions at once, each on its own connection.
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
 * Takes whatever appends are queued and commits them together: [group] writes them all on one connection in one round
 * trip, and one commit follows. There is no timer and no thread of its own: an append that finds a place free, and
 * appends queued, commits groups on its caller's thread until its own is answered, then gives the place up to an append
 * still waiting. A lone append goes at once, and appends that arrive while a commit is in flight form the next group,
 * as at most [GroupCommit.committers] groups commit at once. A group whose writing fails, as two writers racing for one
 * id do, is rolled back and each of its appends is made [alone], as it would have been without group commit.
 */
internal class Committer(
    private val dataSource: DataSource,
    private val settings: GroupCommit,
    private val group: (Connection, List<Append>) -> List<Result<Either<JournalConflict, Long>>>,
    private val alone: (Append) -> Either<JournalConflict, Long>,
) {
    private class Pending(val append: Append) {
        val answer = Promise<Throwable, Either<JournalConflict, Long>>("surefold.journal.append")
    }

    private val queue = ConcurrentLinkedQueue<Pending>()

    // How many appends are committing groups now, each on its own caller's thread.
    private val committing = Shared(0, "surefold.journal.committers")

    /** How many appends wait for a committer, for tests. */
    fun queued(): Int = queue.size

    fun append(append: Append): Either<JournalConflict, Long> {
        val pending = Pending(append)
        queue.add(pending)
        while (true) {
            // Answered, or a place to commit from: whichever comes first. A wait wakes when either changes.
            val answered =
                transact("surefold.journal.committers") {
                    pending.answer.peek()
                        ?: if (committing.get() < settings.committers && queue.isNotEmpty()) {
                            committing.set(committing.get() + 1)
                            null
                        } else {
                            pending.answer.await()
                        }
                }
            if (answered != null) return answered.fold({ throw it }, { it })
            try {
                commitUntil(pending)
            } finally {
                committing.update { it - 1 }
            }
        }
    }

    /** Commits groups until [own] is answered, or until nothing is queued because another group holds it. */
    private fun commitUntil(own: Pending) {
        while (own.answer.peek() == null) {
            val taken = generateSequence { queue.poll() }.take(settings.maxAppends).toList()
            if (taken.isEmpty()) return
            commit(taken)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Whatever failed is every waiting append's to throw, never lost here.
    private fun commit(taken: List<Pending>) {
        try {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                val results =
                    try {
                        group(connection, taken.map { it.append })
                    } catch (raced: SQLException) {
                        connection.rollback()
                        logDebug(
                            "journal: a group of ${taken.size} failed as one (${raced.message}); making each alone",
                        )
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
                    result.fold({ pending.answer.succeed(it) }, { pending.answer.fail(it) })
                }
            }
        } catch (failed: Throwable) {
            // No append is answered as written unless its group committed.
            taken.forEach { it.answer.fail(failed) }
        }
    }

    @Suppress("TooGenericExceptionCaught") // As above: the append's own failure is its caller's to throw.
    private fun answerAlone(pending: Pending) {
        try {
            pending.answer.succeed(alone(pending.append))
        } catch (failed: Throwable) {
            pending.answer.fail(failed)
        }
    }
}
