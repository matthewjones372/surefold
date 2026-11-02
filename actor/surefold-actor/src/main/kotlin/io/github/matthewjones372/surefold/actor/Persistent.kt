package io.github.matthewjones372.surefold.actor

import dev.surefold.fail.Fail
import dev.surefold.fail.bind
import dev.surefold.logError

/**
 * A persistent actor's state: the [value] its events built, the sequence number of the last of them, and the last
 * sequence number it handled from each producer that sends to it reliably (spec 0079).
 */
data class Remembered<S>(val value: S, val sequence: Long, val delivered: Map<String, Long> = emptyMap())

/** How a persistent actor's state becomes bytes for a snapshot, and back. */
interface StateCodec<S> {
    fun encode(state: S): ByteArray

    fun decode(bytes: ByteArray): S
}

/** When a persistent actor saves its state: after each [every]th event, as bytes through [codec] (spec 0074). */
class Snapshotting<S> internal constructor(val every: Long, val codec: StateCodec<S>, val prune: Prune)

/**
 * Whether, and how far, a saved snapshot lets its entity's older events go (specs 0076 and 0077): the feed offset
 * pruning may reach now, or null to keep every event.
 */
fun interface Prune {
    fun readTo(): Long?

    /** How far [id]'s events may go: a journal across databases (spec 0088) answers by [id]'s own database's feed. */
    fun readTo(id: PersistenceId): Long? = readTo()

    companion object {
        /** Keeps every event. */
        val never: Prune = Prune { null }

        /** Deletes what the snapshot before the newest covers, whatever has read it. */
        val always: Prune = Prune { Long.MAX_VALUE }

        /**
         * As [always], but no further than every one of [names] has read, by the offsets their projections save to
         * [offsets]. A name with no offset saved holds back everything.
         */
        fun after(offsets: OffsetStore, vararg names: String): Prune {
            require(names.isNotEmpty()) { "pruning after no read model is Prune.always" }
            val waited = names.toList()
            return Prune { waited.minOf { offsets.load(it) ?: 0 } }
        }

        /**
         * As [after], for a [journal] across databases (spec 0088): each id waits only on what [names] have read of
         * its own database, by the offsets saved under [ShardedJournal.progress].
         */
        fun after(offsets: OffsetStore, journal: ShardedJournal, vararg names: String): Prune {
            require(names.isNotEmpty()) { "pruning after no read model is Prune.always" }
            val waited = names.toList()
            return object : Prune {
                // Without an id there is no database to ask, so nothing goes.
                override fun readTo(): Long? = null

                override fun readTo(id: PersistenceId): Long {
                    val database = journal.database(id)
                    return waited.minOf { offsets.load(ShardedJournal.progress(it, database)) ?: 0 }
                }
            }
        }

        /**
         * As [after], for read models split into [partitions] by slice (spec 0106): each id waits only on what the
         * partition of [names] that follows it has read of its own database, by the offsets saved under
         * [ShardedJournal.progress] with the partition.
         */
        fun after(offsets: OffsetStore, journal: ShardedJournal, partitions: Int, vararg names: String): Prune {
            require(names.isNotEmpty()) { "pruning after no read model is Prune.always" }
            val waited = names.toList()
            return object : Prune {
                override fun readTo(): Long? = null

                override fun readTo(id: PersistenceId): Long {
                    val database = journal.database(id)
                    val partition = Slices.partitionOf(id, partitions)
                    return waited.minOf { offsets.load(ShardedJournal.progress(it, database, partition)) ?: 0 }
                }
            }
        }
    }
}

/**
 * A snapshot after every [n]th event, the state written by [codec]. With a [prune] other than [Prune.never], a journal
 * that is a [JournalPruning] deletes, once each snapshot is saved, the events the one before it covers, as far as
 * [prune] allows (specs 0076 and 0077).
 */
fun <S> every(n: Int, codec: StateCodec<S>, prune: Prune = Prune.never): Snapshotting<S> {
    require(n > 0) { "a snapshot every $n events is never" }
    return Snapshotting(n.toLong(), codec, prune)
}

/** What a command does: persist events, or not, and what runs once they are written and applied. */
class Effect<out Ev, S> internal constructor(
    internal val events: List<Ev>,
    internal val next: Next<Nothing>?,
    internal val then: ((S) -> Unit)?,
) {
    /** Runs [action] with the state after the events, once they are written and applied. */
    fun then(action: (state: S) -> Unit): Effect<Ev, S> {
        val before = then
        return Effect(events, next) { state ->
            before?.invoke(state)
            action(state)
        }
    }
}

/** The effects a persistent actor's command can answer. */
class Effects<Ev, S> internal constructor() {
    /** Writes [events] to the journal, then applies them to the state. */
    fun persist(vararg events: Ev): Effect<Ev, S> = Effect(events.toList(), null, null)

    /** Writes nothing, and keeps the state. */
    fun none(): Effect<Ev, S> = Effect(emptyList(), null, null)

    /** The command is not this actor's to handle: it goes where unhandled messages go. */
    fun unhandled(): Effect<Ev, S> = Effect(emptyList(), Next.Unhandled, null)

    /** Writes nothing, and stops the actor. */
    fun stop(): Effect<Ev, S> = Effect(emptyList(), Next.Stop, null)
}

/** How a persistent actor begins: by reading its journal, or by guessing it has none (spec 0198). */
class Start private constructor(internal val guessing: Boolean) {
    companion object {
        /** Reads the journal before the first command: every restart begins this way. */
        val recovering = Start(false)

        /**
         * Reads nothing on the actor's first start, which begins at `empty`; its start hooks run with that. The first
         * command shows whether the guess was right: if it persists and the append, expecting nothing before it, is
         * accepted, the entity was new. If the append conflicts, or the command persists nothing, the actor restarts
         * without asking its supervision, recovers, and is handed the same command again. For an entity created just
         * before its first command, such as a saga with a fresh id.
         */
        val assumingNew = Start(true)
    }
}

/** A guess of [Start.assumingNew] shown wrong: the actor's runtime restarts it and hands it the command again. */
internal object WrongGuess : Throwable(null, null, false, false)

/** A context that knows whether its actor's start follows a restart, which no guess may skip. */
internal interface Restartable {
    val restarted: Boolean
}

/**
 * A behaviour remembered by its events. On start, and after every restart, it replays its events from its scope's
 * journal through [event] before its first command. A command answers an [Effect]: `persist` writes its events on the
 * turn's own thread, applies them, and then runs the effect's `then`; the next command sees the new state. An append
 * that conflicts with another writer is raised as the behaviour's failure, so supervision decides.
 *
 * With [snapshots], and a scope that has a store, a persist that carries the sequence number across a multiple of
 * its `every` saves the state it reached, before `then`; a start loads the newest snapshot and replays only the
 * events after it. A save that throws is logged and the turn goes on: the events are already written, and the next
 * multiple saves again.
 *
 * A [Delivered] command whose sequence number is no greater than the last this actor handled from its producer is
 * dropped without a turn (spec 0079). One that persists events writes a mark of its delivery in the same append, so
 * the actor remembers it across restarts and moves, as a snapshot does; one that persists nothing is remembered until
 * the actor stops, and handled again after that, where it changes nothing the journal holds. One its turn stashes is
 * not remembered until it is handled once put back.
 *
 * With [start] of [Start.assumingNew], an actor's first start reads nothing until its first command, or its first
 * run of commands with a [batch], shows whether it is new.
 */
fun <M : Any, Ev, S> persistent(
    id: PersistenceId,
    empty: S,
    codec: EventCodec<Ev>,
    command: Effects<Ev, S>.(ctx: Ctx<M>, state: S, command: M) -> Effect<Ev, S>,
    event: (state: S, event: Ev) -> S,
    snapshots: Snapshotting<S>? = null,
    /**
     * How many of the commands already waiting are decided before one append writes all their events (spec 0086).
     * With more than 1, each command is still decided against the state the one before it left, and each `then` runs
     * after the append, in order; a conflict fails the whole batch.
     */
    batch: Int = 1,
    start: Start = Start.recovering,
): Behaviour<M, Remembered<S>, JournalConflict> {
    require(batch > 0) { "a batch of $batch commands decides none" }
    val initial = Remembered(empty, 0)
    val effects = Effects<Ev, S>()
    val turns: (Fail<JournalConflict>.(Ctx<M>, Remembered<S>, Run<M>) -> Batched<Remembered<S>>)? =
        if (batch == 1) {
            null
        } else {
            { ctx, remembered, messages ->
                val deciding = Deciding(effects, command, event, codec, ctx, remembered)
                // One append for the batch, so one context: the first command's (spec 0122).
                val guessed = start.guessing && remembered === initial
                messages.within(0) { decided(id, snapshots, deciding, ctx, remembered, messages, guessed) }
            }
        }
    return Behaviour<M, Remembered<S>, JournalConflict>(
        initial = initial,
        turn = { ctx, remembered, message ->
            val offered = (message as? Delivered)?.delivery
            if (remembered.handled(offered)) {
                Next.Stay
            } else {
                val stashing = Stashing(ctx)
                val effect = effects.command(stashing, remembered.value, message)
                // A command stashed is handled when it is put back: until then its delivery is not remembered.
                val delivery = offered?.takeUnless { stashing.kept(message) }
                // Still on a guess that the journal is empty: an answer from it is only safe once an append proves it.
                val guessed = start.guessing && remembered === initial
                if (guessed && effect.events.isEmpty()) throw WrongGuess
                val after = when {
                    effect.events.isNotEmpty() ->
                        written(id, codec, event, snapshots, ctx, remembered, effect.events, delivery, guessed)

                    delivery != null -> remembered.copy(delivered = remembered.delivered.after(delivery))

                    else -> remembered
                }
                effect.then?.invoke(after.value)
                effect.next ?: if (after === remembered) Next.Stay else Next.Become(after)
            }
        },
        start = { ctx, _ -> begun(start, ctx) { recovered(id, empty, codec, event, snapshots, ctx) } },
        signal = null,
        turns = turns,
        batch = batch,
    )
}

/** Nothing read, on a first start that guesses the entity is new; what [recover] reads, on every other. */
private inline fun <S> begun(start: Start, ctx: Ctx<*>, recover: () -> S): Next<S> =
    if (start.guessing && (ctx as? Restartable)?.restarted == false) Next.Stay else Next.Become(recover())

/**
 * [events] and the mark of [delivery] appended after [remembered], and the state they reach, saved as a snapshot when
 * it is due. On a [guessed] state, a conflict is the guess shown wrong rather than a failure.
 */
private fun <M : Any, Ev, S> Fail<JournalConflict>.written(
    id: PersistenceId,
    codec: EventCodec<Ev>,
    event: (state: S, event: Ev) -> S,
    snapshots: Snapshotting<S>?,
    ctx: Ctx<M>,
    remembered: Remembered<S>,
    events: List<Ev>,
    delivery: Delivery?,
    guessed: Boolean,
): Remembered<S> {
    val written = events.map(codec::encode) + listOfNotNull(delivery?.let(::mark))
    val appended = ctx.journal.append(id, remembered.sequence, written)
    if (guessed && appended.isLeft()) throw WrongGuess
    val value = events.fold(remembered.value, event)
    return Remembered(value, appended.bind(), remembered.delivered.after(delivery)).also { reached ->
        if (snapshots != null) ctx.snapshots?.snapshot(id, remembered.sequence, reached, snapshots, ctx.journal)
    }
}

/** The newest snapshot, if any, and every event after it folded in: where a start and a restart begin. */
private fun <M : Any, Ev, S> recovered(
    id: PersistenceId,
    empty: S,
    codec: EventCodec<Ev>,
    event: (state: S, event: Ev) -> S,
    snapshots: Snapshotting<S>?,
    ctx: Ctx<M>,
): Remembered<S> {
    val from = snapshots?.let { ctx.snapshots?.latest(id) }
        ?.let { decode(snapshots.codec, it.bytes, it.sequence) }
        ?: Remembered(empty, 0)
    val stored = ctx.journal.read(id, from.sequence + 1)
    val first = stored.firstOrNull()?.sequence ?: (from.sequence + 1)
    check(first == from.sequence + 1) {
        "$id cannot be recovered: events ${from.sequence + 1} to ${first - 1} were deleted, and no snapshot covers them"
    }
    return stored.fold(from) { state, kept ->
        try {
            state.replay(kept, codec, event)
        } catch (unread: UnreadableEvent) {
            throw IllegalStateException("$id cannot be recovered: event ${kept.sequence} is ${unread.message}", unread)
        }
    }
}

/** One command of a batch once decided: what it answered, and the state it left, for its `then`. */
private class Decided<Ev, S>(val effect: Effect<Ev, S>, val after: S)

/** A batch being decided: the state each command leaves for the next, and what the append will write. */
private class Deciding<M : Any, Ev, S>(
    private val effects: Effects<Ev, S>,
    private val command: Effects<Ev, S>.(ctx: Ctx<M>, state: S, command: M) -> Effect<Ev, S>,
    private val event: (state: S, event: Ev) -> S,
    private val codec: EventCodec<Ev>,
    ctx: Ctx<M>,
    remembered: Remembered<S>,
) {
    private val ctx = Stashing(ctx)
    var value: S = remembered.value
    var delivered: Map<String, Long> = remembered.delivered
    val written = ArrayList<ByteArray>()
    val decided = ArrayList<Decided<Ev, S>>()
    val unhandled = ArrayList<Any>()
    var stopped = false

    /** [message] decided against the state so far, unless its delivery was already handled. */
    fun take(message: M) {
        val delivery = (message as? Delivered)?.delivery
        if (delivery == null || delivery.sequence > (delivered[delivery.producer] ?: 0)) decide(message, delivery)
    }

    private fun decide(message: M, offered: Delivery?) {
        val effect = effects.command(ctx, value, message)
        val delivery = offered?.takeUnless { ctx.kept(message) }
        if (effect.next === Next.Unhandled) {
            unhandled += message
        } else {
            if (effect.events.isNotEmpty()) {
                written += effect.events.map(codec::encode)
                delivery?.let { written += mark(it) }
                value = effect.events.fold(value, event)
            }
            delivered = delivered.after(delivery)
            decided += Decided(effect, value)
            stopped = effect.next === Next.Stop
        }
    }
}

/**
 * A batch of [messages] decided in turn, each against the state the one before left, and every event and delivery
 * mark among them written in one append; then each `then` runs, in order, with the state its own command left. On a
 * [guessed] state, a batch that writes nothing, or whose append conflicts, is the guess shown wrong: no `then` has run,
 * and the whole batch is handed back once the actor has recovered.
 */
private fun <M : Any, Ev, S> Fail<JournalConflict>.decided(
    id: PersistenceId,
    snapshots: Snapshotting<S>?,
    deciding: Deciding<M, Ev, S>,
    ctx: Ctx<M>,
    remembered: Remembered<S>,
    messages: List<M>,
    guessed: Boolean,
): Batched<Remembered<S>> {
    var taken = 0
    while (taken < messages.size && !deciding.stopped) deciding.take(messages[taken++])
    val sequence = appended(id, ctx, remembered.sequence, deciding.written, guessed)
    val reached = Remembered(deciding.value, sequence, deciding.delivered)
    if (snapshots != null && sequence != remembered.sequence) {
        ctx.snapshots?.snapshot(id, remembered.sequence, reached, snapshots, ctx.journal)
    }
    deciding.decided.forEach { it.effect.then?.invoke(it.after) }
    val next = when {
        deciding.stopped -> Next.Stop
        reached == remembered -> Next.Stay
        else -> Next.Become(reached)
    }
    return Batched(next, deciding.unhandled, messages.drop(taken))
}

/**
 * The sequence [written] reaches once appended after [sequence], which is [sequence] itself for nothing to write. On a
 * [guessed] state, as for one command, only an append accepted after nothing shows the answers decided from it right:
 * writing nothing, or a conflict, is the guess shown wrong.
 */
private fun Fail<JournalConflict>.appended(
    id: PersistenceId,
    ctx: Ctx<*>,
    sequence: Long,
    written: List<ByteArray>,
    guessed: Boolean,
): Long {
    if (written.isEmpty()) return if (guessed) throw WrongGuess else sequence
    val appended = ctx.journal.append(id, sequence, written)
    if (guessed && appended.isLeft()) throw WrongGuess
    return appended.bind()
}

/** Whether [delivery] is one this state already handled: no later than the last from its producer. */
private fun Remembered<*>.handled(delivery: Delivery?): Boolean =
    delivery != null && delivery.sequence <= (delivered[delivery.producer] ?: 0)

/** This state after [kept]: an event applied through [event], or the mark of a delivery remembered. */
private fun <Ev, S> Remembered<S>.replay(kept: StoredEvent, codec: EventCodec<Ev>, event: (S, Ev) -> S): Remembered<S> =
    if (isDeliveryMark(kept.bytes)) {
        val (producer, sequence) = unmark(kept.bytes)
        copy(delivered = delivered + (producer to sequence), sequence = kept.sequence)
    } else {
        // An event upgraded to several applies each, in order, under the sequence number it was written with.
        copy(value = codec.decodeAll(kept.bytes).fold(value, event), sequence = kept.sequence)
    }

/** These deliveries, with [delivery] the last handled from its producer. */
private fun Map<String, Long>.after(delivery: Delivery?): Map<String, Long> =
    if (delivery == null) this else this + (delivery.producer to delivery.sequence)

/**
 * Saves [reached] if the persist from [before] crossed a multiple of [how]'s `every`, and then, when [how] prunes,
 * deletes from [journal] the events the snapshot before it covers. A failed save or deletion is logged.
 */
private fun <S> SnapshotStore.snapshot(
    id: PersistenceId,
    before: Long,
    reached: Remembered<S>,
    how: Snapshotting<S>,
    journal: Journal,
) {
    if (before / how.every == reached.sequence / how.every) return
    val saved = logged("the snapshot of $id at ${reached.sequence} was not saved; the next one will be") {
        save(id, reached.sequence, encode(how.codec, reached))
    }
    val pruning = journal as? JournalPruning
    if (saved && how.prune !== Prune.never && pruning != null) {
        val upTo = reached.sequence - how.every
        logged("the events of $id up to $upTo were not deleted; the next snapshot will try again") {
            how.prune.readTo(id)?.let { readTo -> pruning.deleteTo(id, upTo, readTo) }
        }
    }
}

/** Whether [work] finished; what it threw is logged as [failed], except an interrupt, which still stops the turn. */
private inline fun logged(failed: String, work: () -> Unit): Boolean =
    try {
        work()
        true
    } catch (stopping: InterruptedException) {
        throw stopping
    } catch (thrown: Exception) {
        logError(failed, thrown)
        false
    }
