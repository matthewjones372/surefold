package dev.surefold.cluster.saga

import dev.surefold.Settled
import dev.surefold.actor.Journal
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.StateCodec
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import dev.surefold.fail.fold
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import java.util.UUID

/** A saga whose steps are journalled, so a run that dies resumes from the last step it recorded (spec 0299). */
class Durable<I, E, A> internal constructor(
    val kind: String,
    internal val input: StateCodec<I>,
    internal val error: StateCodec<E>,
    internal val answer: StateCodec<A>,
    internal val body: DurableScope<E>.(I) -> A,
)

/**
 * A durable saga of [kind]: [body] is plain blocking code whose steps are journalled as they begin and end. Run again
 * with the same id after a crash, the body runs from the top, and each step already ended answers what it answered
 * then without calling anything.
 */
fun <I, E, A> durableSaga(
    kind: String,
    input: StateCodec<I>,
    error: StateCodec<E>,
    answer: StateCodec<A>,
    body: DurableScope<E>.(I) -> A,
): Durable<I, E, A> = Durable(kind, input, error, answer, body)

/** What a durable saga's body can do: fail, take a journalled step, and read a time or an id that replay repeats. */
interface DurableScope<E> : Fail<E> {
    /** The saga's id, which every step's key starts with. */
    val id: String

    /**
     * Runs [action] with the key `"$id/$name"` and journals what it returned through [codec]. A step that began but
     * has no recorded end is unknown when the saga resumes: [check] settles it, and without one the saga stops
     * [Unsettled] before calling anything.
     */
    fun <B> step(
        name: String,
        codec: StateCodec<B>,
        action: (key: String) -> B,
        check: ((key: String) -> Settled<B>)? = null,
    ): B

    /** The clock's time, journalled the first time and the same on every replay. */
    fun now(): Instant

    /** A random id, journalled the first time and the same on every replay. */
    fun uuid(): String
}

/** A resumed saga's body called [called] where its journal holds [journalled]: its code changed under it. */
class Diverged(val saga: String, val journalled: String, val called: String) :
    IllegalStateException("saga $saga called $called where its journal holds $journalled")

/** A resumed saga met a step that began, may have happened, and has no check to say: nothing more is called. */
class Unsettled(val saga: String, val step: String) :
    IllegalStateException("saga $saga can't tell whether $step happened, and has no check to ask")

/** Another runner wrote to this saga's journal meanwhile: this one stops rather than write a second history. */
class Overtaken(val saga: String) : IllegalStateException("saga $saga was written by another runner")

/**
 * Runs the saga [id] on [journal], or resumes it: an ended saga answers as it ended without running anything, a
 * started one replays its journal, and a new one is started with [input]. A declared fail ends it on the left.
 */
fun <I, E, A> Durable<I, E, A>.run(journal: Journal, id: String, input: I): Either<E, A> {
    val log = Log(journal, PersistenceId(kind, id))
    log.ending()?.let { return it.decoded(this) }
    val begun = log.started() ?: log.start(this.input.encode(input))
    val answer = attempt<E, A> { Replay(this, id, log).body(this@run.input.decode(begun)) }
    log.append(answer.fold({ Record.Refused(error.encode(it)) }, { Record.Finished(this.answer.encode(it)) }))
    return answer
}

private fun <I, E, A> Record.decoded(saga: Durable<I, E, A>): Either<E, A> = when (this) {
    is Record.Finished -> Either.Right(saga.answer.decode(bytes))
    is Record.Refused -> Either.Left(saga.error.decode(bytes))
    else -> error("$this does not end a saga")
}

private class Replay<E>(private val outer: Fail<E>, override val id: String, private val log: Log) : DurableScope<E> {
    private val names = mutableSetOf<String>()
    private var tick = 0

    override fun fail(error: E): Nothing = outer.fail(error)

    override fun <B> step(
        name: String,
        codec: StateCodec<B>,
        action: (String) -> B,
        check: ((String) -> Settled<B>)?,
    ): B {
        require(names.add(name)) { "saga $id already has a step named $name" }
        val key = "$id/$name"
        return when (val kept = log.next()) {
            null -> fresh(name, codec) { action(key) }

            is Record.Ended -> codec.decode(kept.named(name).bytes)

            is Record.Begun -> {
                kept.named(name)
                when (val ended = log.next()) {
                    is Record.Ended -> codec.decode(ended.named(name).bytes)
                    null -> settle(name, key, codec, action, check)
                    else -> throw Diverged(id, "$ended", name)
                }
            }

            else -> throw Diverged(id, "$kept", name)
        }
    }

    private fun <B> settle(
        name: String,
        key: String,
        codec: StateCodec<B>,
        action: (String) -> B,
        check: ((String) -> Settled<B>)?,
    ): B {
        val settled = check?.invoke(key) ?: throw Unsettled(id, name)
        val value = when (settled) {
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

    override fun uuid(): String = taken("uuid#${tick++}") { UUID.randomUUID().toString() }

    // A time or an id can't be unknown, so it is journalled once, as an end with no beginning.
    private fun taken(name: String, make: () -> String): String = when (val kept = log.next()) {
        null -> make().also { log.append(Record.Ended(name, it.toByteArray())) }
        is Record.Ended -> String(kept.named(name).bytes)
        else -> throw Diverged(id, "$kept", name)
    }

    private fun <R : Record.Named> R.named(name: String): R =
        also { if (this.name != name) throw Diverged(id, this.name, name) }
}

/** The saga's journal, read once and then followed by each append, with a cursor replay walks along. */
private class Log(private val journal: Journal, private val id: PersistenceId) {
    private val records = journal.read(id).map { Record.read(it.bytes) }
    private var sequence = records.size.toLong()
    private var at = 1

    fun ending(): Record? = records.lastOrNull()?.takeIf { it is Record.Finished || it is Record.Refused }

    fun started(): ByteArray? = (records.firstOrNull() as? Record.Started)?.input

    fun start(input: ByteArray): ByteArray = input.also { append(Record.Started(input)) }

    fun next(): Record? = records.getOrNull(at)?.also { at++ }

    fun append(record: Record) {
        sequence = journal.append(id, sequence, listOf(record.bytes())).fold({ throw Overtaken(id.id) }, { it })
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
                else -> error("no saga record has the tag $tag")
            }
        }
    }
}
