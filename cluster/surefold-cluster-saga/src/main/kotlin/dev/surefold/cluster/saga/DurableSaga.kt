package dev.surefold.cluster.saga

import dev.surefold.Policy
import dev.surefold.Settled
import dev.surefold.Undo
import dev.surefold.actor.Journal
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.StateCodec
import dev.surefold.clock
import dev.surefold.counter
import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import dev.surefold.fail.fold
import dev.surefold.fail.nonFatalOrThrow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/** A saga whose steps are journalled, so a run that dies resumes from the last step it recorded (spec 0299). */
class Durable<I, E, A>
internal constructor(
    val kind: String,
    internal val input: StateCodec<I>,
    internal val error: StateCodec<E>,
    internal val answer: StateCodec<A>,
    internal val body: DurableScope<E>.(I) -> A,
    internal val undo: Policy?,
) {
    /** How a saga stands, as `Sagas.await` answers from any node. */
    sealed interface Outcome<out E, out A> {
        data class Done<out A>(val value: A) : Outcome<Nothing, A>

        data class Refused<out E>(val error: E) : Outcome<E, Nothing>

        /** Not ended yet, or not answered within the wait. */
        data object Running : Outcome<Nothing, Nothing>

        /** Stopped [Stuck], [Unsettled] or [Diverged], undoing nothing further until `resume`. */
        data class Stuck(val reason: String) : Outcome<Nothing, Nothing>

        /** The journal holds nothing of it: never started, or pruned. */
        data object Forgotten : Outcome<Nothing, Nothing>
    }
}

/**
 * A durable saga of [kind]: [body] is plain blocking code whose steps are journalled as they begin and end. Run again
 * with the same id after a crash, the body runs from the top, and each step already ended answers what it answered then
 * without calling anything.
 */
fun <I, E, A> durableSaga(
    kind: String,
    input: StateCodec<I>,
    error: StateCodec<E>,
    answer: StateCodec<A>,
    undo: Policy? = null,
    body: DurableScope<E>.(I) -> A,
): Durable<I, E, A> = Durable(kind, input, error, answer, body, undo)

/** What a durable saga's body can do: fail, take a journalled step, and read a time or an id that replay repeats. */
interface DurableScope<E> : Fail<E> {
    /** The saga's id, which every step's key starts with. */
    val id: String

    /**
     * Runs [action] with the key `"$id/$name"` and journals what it returned through [codec]. A step that began but has
     * no recorded end is unknown when the saga resumes: [check] settles it, and without one the saga stops [Unsettled]
     * before calling anything. Should the saga fail with a declared error, [undo] is handed the key `"$id/$name/undo"`,
     * what [action] returned and why, and its end is journalled too.
     */
    fun <B> step(
        name: String,
        codec: StateCodec<B>,
        action: (key: String) -> B,
        check: ((key: String) -> Settled<B>)? = null,
        undo: ((key: String, value: B, why: Undo<E>) -> Unit)? = null,
    ): B

    /** The clock's time, journalled the first time and the same on every replay. */
    fun now(): Instant

    /**
     * A UUID v7 (RFC 9562): the clock's millisecond first, so ids sort by when they were made, then random bits.
     * Journalled the first time and the same on every replay.
     */
    fun uuid(): String
}

/** A resumed saga's body called [called] where its journal holds [journalled]: its code changed under it. */
class Diverged(val saga: String, val journalled: String, val called: String) :
    IllegalStateException("saga $saga called $called where its journal holds $journalled")

/** A resumed saga met a step that began, may have happened, and has no check to say: nothing more is called. */
class Unsettled(val saga: String, val step: String) :
    IllegalStateException("saga $saga can't tell whether $step happened, and has no check to ask")

/**
 * An undo of [step] still failed after the saga's undo policy: the steps before it are not undone either. The saga
 * stays as its journal holds it, and [resume] tries the undo again.
 */
class Stuck(val saga: String, val step: String, cause: Throwable) :
    IllegalStateException("saga $saga is stuck undoing $step", cause)

/** Another runner wrote to this saga's journal meanwhile: this one stops rather than write a second history. */
class Overtaken(val saga: String) : IllegalStateException("saga $saga was written by another runner")

/**
 * Runs the saga [id] on [journal], or resumes it: an ended saga answers as it ended without running anything, a started
 * one replays its journal, and a new one is started with [input]. A declared fail ends it on the left.
 */
fun <I, E, A> Durable<I, E, A>.run(journal: Journal, id: String, input: I): Either<E, A> =
    checkNotNull(drive(journal, id, this.input.encode(input)))

/** Runs the started saga [id] again: after a crash, or once whatever left it [Stuck] is fixed. */
fun <I, E, A> Durable<I, E, A>.resume(journal: Journal, id: String): Either<E, A> =
    requireNotNull(drive(journal, id, null)) { "saga $kind/$id was never started" }

/**
 * Runs, resumes or answers the saga [id], starting it with [input] if its journal holds nothing; null if it holds
 * nothing and there is no [input]. [starting] is called before `Started` is written, [started] once it is there.
 */
internal fun <I, E, A> Durable<I, E, A>.drive(
    journal: Journal,
    id: String,
    input: ByteArray?,
    starting: () -> Unit = {},
    started: () -> Unit = {},
): Either<E, A>? {
    val log = Log(journal, PersistenceId(kind, id))
    val begun = log.started() ?: input?.also { starting() }?.let(log::start) ?: return null
    started()
    log.ending()?.let {
        return it.decoded(this)
    }
    return ran(log, id, begun)
}

/**
 * How the saga [id] ends when driven on this node, as its entity answers it; null when another runner overtook it.
 * Whatever stops it short of an end, a [Stuck] or the journal failing, leaves it [Ending.Stuck] here.
 */
internal fun <I, E, A> Durable<I, E, A>.ending(
    journal: Journal,
    id: String,
    input: ByteArray?,
    starting: () -> Unit,
    started: () -> Unit,
): Ending? =
    try {
        when (val ended = drive(journal, id, input, starting, started)) {
            null -> Ending.Forgotten
            is Either.Left -> Ending.Refused(error.encode(ended.value))
            is Either.Right -> Ending.Done(answer.encode(ended.value))
        }
    } catch (_: Overtaken) {
        null
    } catch (stopped: Exception) {
        Ending.Stuck(stopped.nonFatalOrThrow().message ?: stopped.toString())
    }

private fun <I, E, A> Durable<I, E, A>.ran(log: Log, id: String, begun: ByteArray): Either<E, A> {
    val replay = Replay(this, id, log)
    val answer = attempt<E, A> { replay.scoped(this).body(input.decode(begun)) }
    answer.fold({ refused -> undoAll(log, replay, refused) }, { log.append(Record.Finished(this.answer.encode(it))) })
    return answer
}

/** Each step's undo, newest first, each journalled once it ends; a resume skips the ones already undone. */
private fun <I, E, A> Durable<I, E, A>.undoAll(log: Log, replay: Replay<E>, refused: E) {
    if (!log.undoing()) log.append(Record.Undoing(error.encode(refused)))
    val why = Undo.Failed(refused)
    replay.done
        .asReversed()
        .filterNot { it.name in log.undone() }
        .forEach { step ->
            try {
                undo?.invoke { step.undo(why) } ?: step.undo(why)
            } catch (failed: Exception) {
                stuck(step.name, failed)
            }
            log.append(Record.Undone(step.name))
        }
    log.append(Record.Refused(error.encode(refused)))
}

private fun <I, E, A> Durable<I, E, A>.stuck(step: String, failed: Throwable): Nothing {
    counter("surefold.saga.stuck", "saga" to kind).increment(1.0)
    throw Stuck(kind, step, failed)
}

private fun <I, E, A> Record.decoded(saga: Durable<I, E, A>): Either<E, A> =
    when (this) {
        is Record.Finished -> Either.Right(saga.answer.decode(bytes))
        is Record.Refused -> Either.Left(saga.error.decode(bytes))
        else -> error("$this does not end a saga")
    }

/** A step that ended, with what its undo needs. */
private class Done<E>(val name: String, val undo: (Undo<E>) -> Unit)

private class Replay<E>(private val saga: Durable<*, E, *>, override val id: String, private val log: Log) :
    DurableScope<E> {
    private val names = mutableSetOf<String>()
    private var tick = 0
    private lateinit var outer: Fail<E>
    val done = mutableListOf<Done<E>>()

    fun scoped(fail: Fail<E>): Replay<E> = also { outer = fail }

    override fun fail(error: E): Nothing = outer.fail(error)

    override fun <B> step(
        name: String,
        codec: StateCodec<B>,
        action: (String) -> B,
        check: ((String) -> Settled<B>)?,
        undo: ((String, B, Undo<E>) -> Unit)?,
    ): B {
        require(names.add(name)) { "saga $id already has a step named $name" }
        val key = "$id/$name"
        val value =
            when (val kept = log.next()) {
                null -> fresh(name, codec) { action(key) }

                is Record.Ended -> codec.decode(kept.named(name).bytes)

                is Record.Begun -> {
                    kept.named(name)
                    when (val ended = log.next()) {
                        is Record.Ended -> codec.decode(ended.named(name).bytes)

                        // The step failed with this declared error the first time: it did not happen, so it fails
                        // again.
                        is Record.Undoing -> fail(saga.error.decode(ended.bytes))

                        null -> settle(name, key, codec, action, check)

                        else -> throw Diverged(id, "$ended", name)
                    }
                }

                else -> throw Diverged(id, "$kept", name)
            }
        undo?.let { done += Done(name) { why -> it("$key/undo", value, why) } }
        return value
    }

    private fun <B> settle(
        name: String,
        key: String,
        codec: StateCodec<B>,
        action: (String) -> B,
        check: ((String) -> Settled<B>)?,
    ): B {
        val settled = check?.invoke(key) ?: throw Unsettled(id, name)
        val value =
            when (settled) {
                is Settled.Happened -> settled.value
                Settled.DidNot -> action(key)
            }
        log.append(Record.Ended(name, codec.encode(value)))
        return value
    }

    private fun <B> fresh(name: String, codec: StateCodec<B>, action: () -> B): B {
        log.append(Record.Begun(name))
        return action().also { log.append(Record.Ended(name, codec.encode(it))) }
    }

    override fun now(): Instant =
        Instant.ofEpochMilli(taken("now#${tick++}") { clock.get().now().toEpochMilli().toString() }.toLong())

    override fun uuid(): String = taken("uuid#${tick++}") { uuidV7(clock.get().now().toEpochMilli()).toString() }

    // A time or an id can't be unknown, so it is journalled once, as an end with no beginning.
    private fun taken(name: String, make: () -> String): String =
        when (val kept = log.next()) {
            null -> make().also { log.append(Record.Ended(name, it.toByteArray())) }
            is Record.Ended -> String(kept.named(name).bytes)
            else -> throw Diverged(id, "$kept", name)
        }

    private fun <R : Record.Named> R.named(name: String): R = also {
        if (this.name != name) throw Diverged(id, this.name, name)
    }
}

/** The saga's journal, read once and then followed by each append, with a cursor replay walks along. */
private class Log(private val journal: Journal, private val id: PersistenceId) {
    private val records = journal.read(id).map { Record.read(it.bytes) }
    private var sequence = records.size.toLong()
    private var at = 1

    fun ending(): Record? = records.lastOrNull()?.takeIf { it is Record.Finished || it is Record.Refused }

    fun started(): ByteArray? = (records.firstOrNull() as? Record.Started)?.input

    fun start(input: ByteArray): ByteArray = input.also { append(Record.Started(input)) }

    fun next(): Record? = records.getOrNull(at)?.takeUnless { it is Record.Undone }?.also { at++ }

    fun undoing(): Boolean = records.any { it is Record.Undoing }

    fun undone(): Set<String> = undone

    private val undone = records.filterIsInstance<Record.Undone>().map { it.name }.toMutableSet()

    fun append(record: Record) {
        sequence = journal.append(id, sequence, listOf(record.bytes())).fold({ throw Overtaken(id.id) }, { it })
        if (record is Record.Undone) undone += record.name
    }
}

private sealed interface Record {
    sealed interface Named : Record {
        val name: String
    }

    class Started(val input: ByteArray) : Record

    data class Begun(override val name: String) : Named

    class Ended(override val name: String, val bytes: ByteArray) : Named {
        override fun toString() = "Ended($name)"
    }

    class Finished(val bytes: ByteArray) : Record

    class Refused(val bytes: ByteArray) : Record

    class Undoing(val bytes: ByteArray) : Record

    class Undone(val name: String) : Record

    fun bytes(): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            when (this) {
                is Started -> data.tagged(STARTED, input)

                is Begun -> data.tagged(BEGUN, name.toByteArray())

                is Ended -> {
                    data.tagged(ENDED, name.toByteArray())
                    data.write(bytes)
                }

                is Finished -> data.tagged(FINISHED, bytes)

                is Refused -> data.tagged(REFUSED, bytes)

                is Undoing -> data.tagged(UNDOING, bytes)

                is Undone -> data.tagged(UNDONE, name.toByteArray())
            }
        }
        return out.toByteArray()
    }

    companion object {
        const val STARTED = 1
        const val BEGUN = 2
        const val ENDED = 3
        const val FINISHED = 4
        const val REFUSED = 5
        const val UNDOING = 6
        const val UNDONE = 7

        private fun DataOutputStream.tagged(tag: Int, sized: ByteArray) {
            writeByte(tag)
            writeInt(sized.size)
            write(sized)
        }

        fun read(bytes: ByteArray): Record {
            val data = DataInputStream(ByteArrayInputStream(bytes))
            val tag = data.readByte().toInt()
            val first = data.readNBytes(data.readInt())
            return when (tag) {
                STARTED -> Started(first)
                BEGUN -> Begun(String(first))
                ENDED -> Ended(String(first), data.readAllBytes())
                FINISHED -> Finished(first)
                REFUSED -> Refused(first)
                UNDOING -> Undoing(first)
                UNDONE -> Undone(String(first))
                else -> error("no saga record has the tag $tag")
            }
        }
    }
}

private val random = SecureRandom()

/** RFC 9562's version 7: 48 bits of Unix milliseconds, the version, 12 random bits, the variant, 62 random bits. */
internal fun uuidV7(millis: Long): UUID {
    val high = (millis and 0xFFFF_FFFF_FFFFL shl 16) or (0x7L shl 12) or (random.nextLong() and 0xFFFL)
    val low = (random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL) or Long.MIN_VALUE
    return UUID(high, low)
}
