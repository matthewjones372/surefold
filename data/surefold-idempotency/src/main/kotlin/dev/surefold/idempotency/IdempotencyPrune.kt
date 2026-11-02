package dev.surefold.idempotency

import dev.surefold.jobs.Cron
import dev.surefold.jobs.CronEntry
import dev.surefold.jobs.Handled
import dev.surefold.jobs.JobCodec
import dev.surefold.jobs.JobKind
import dev.surefold.jobs.JobResult

private object NoPayload : JobCodec<Unit> {
    override val version = 1

    override fun encode(payload: Unit) = "{}"

    override fun decode(version: Int, json: String) = Unit
}

/**
 * The job that deletes expired idempotency keys (spec 0226), run on a timetable by a `surefold-jobs` worker. Give the
 * worker [handledBy] a store and [every] a cron; any number of instances prune each firing once.
 */
object IdempotencyPrune : JobKind<Unit>("surefold-idempotency-prune", NoPayload) {

    /** This kind, run by pruning [store]. */
    fun handledBy(store: PostgresIdempotency): Handled<Unit> = handledBy { _ ->
        store.prune()
        JobResult.Done
    }

    /** A cron entry that prunes at every firing of [cron]. */
    fun every(cron: Cron): CronEntry<Unit> = CronEntry(name, cron, this, Unit)
}
