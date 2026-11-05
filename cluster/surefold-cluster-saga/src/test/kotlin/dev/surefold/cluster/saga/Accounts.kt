package dev.surefold.cluster.saga

import dev.surefold.Promise
import dev.surefold.Shared
import dev.surefold.actor.ActorRef
import dev.surefold.actor.Address
import dev.surefold.actor.EventCodec
import dev.surefold.actor.InMemoryJournal
import dev.surefold.actor.Journal
import dev.surefold.actor.JournalConflict
import dev.surefold.actor.KeyAnswer
import dev.surefold.actor.Keyed
import dev.surefold.actor.KeyedEvent
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.Reply
import dev.surefold.actor.StateCodec
import dev.surefold.actor.StoredEvent
import dev.surefold.actor.become
import dev.surefold.actor.behaviour
import dev.surefold.actor.journal
import dev.surefold.actor.keyed
import dev.surefold.actor.keyedEvents
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.remote.codec
import dev.surefold.actor.remote.freePort
import dev.surefold.actor.remote.node
import dev.surefold.cluster.Discovery
import dev.surefold.cluster.Gossiping
import dev.surefold.cluster.Sharded
import dev.surefold.cluster.Status
import dev.surefold.cluster.cluster
import dev.surefold.cluster.sharding
import dev.surefold.fail.Either
import dev.surefold.fail.fold
import dev.surefold.scope
import dev.surefold.surefoldFuture
import dev.surefold.test.eventually
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal sealed interface Move {
    val pence: Long

    data class Debit(override val pence: Long) : Move

    data class Credit(override val pence: Long) : Move
}

/** Why an account says no: a debit past nothing, or a credit past [CAP]. */
internal enum class Refusal {
    Overdrawn,
    Full,
}

/** The most an account holds, so a credit can be refused too and a saga has a debit to undo. */
internal const val CAP = 1_000L

internal typealias Account = Keyed<Move, Refusal>

private object MoveWire : MessageCodec<Move> {
    override fun write(message: Move, out: WireOut) {
        out.boolean(message is Move.Debit)
        out.long(message.pence)
    }

    override fun read(input: WireIn): Move =
        if (input.boolean()) Move.Debit(input.long()) else Move.Credit(input.long())
}

private object RefusalWire : MessageCodec<Refusal> {
    override fun write(message: Refusal, out: WireOut) = out.int(message.ordinal)

    override fun read(input: WireIn): Refusal = Refusal.entries[input.int()]
}

private object Moves : EventCodec<Move> {
    override fun encode(event: Move): ByteArray =
        when (event) {
            is Move.Debit -> "D:${event.pence}"
            is Move.Credit -> "C:${event.pence}"
        }.toByteArray()

    override fun decode(bytes: ByteArray): Move {
        val (kind, pence) = String(bytes).split(":")
        return if (kind == "D") Move.Debit(pence.toLong()) else Move.Credit(pence.toLong())
    }
}

private object Refusals : EventCodec<Refusal> {
    override fun encode(event: Refusal): ByteArray = event.name.toByteArray()

    override fun decode(bytes: ByteArray): Refusal = Refusal.valueOf(String(bytes))
}

internal object Says : StateCodec<String> {
    override fun encode(state: String): ByteArray = state.toByteArray()

    override fun decode(bytes: ByteArray): String = String(bytes)
}

private fun Long.moved(move: Move) =
    when (move) {
        is Move.Debit -> this - move.pence
        is Move.Credit -> this + move.pence
    }

/** A keyed account that holds from nothing to [CAP]. */
private fun keyedAccount(id: String) =
    keyed<Move, Move, Long, Refusal>(
        id = PersistenceId("account", id),
        empty = 0L,
        codec = Moves,
        refusals = Refusals,
        decide = { balance, move ->
            when {
                move is Move.Debit && balance < move.pence -> refuse(Refusal.Overdrawn)
                move is Move.Credit && balance + move.pence > CAP -> refuse(Refusal.Full)
                else -> accept(move)
            }
        },
        event = Long::moved,
    )

/** An answer nobody hears, as one lost on its way back. */
private object Lost : Reply<KeyAnswer<Refusal>> {
    override val address = Address("local", "/lost", 0)

    override fun invoke(answer: KeyAnswer<Refusal>) = Unit
}

/** What a seeded run does to the commands an account is told: loses their answers, or tells them twice. */
internal class Faults(seed: Int, private val lose: Double, private val twice: Double) {
    private val random = Random(seed)

    fun loses(): Boolean = random.nextDouble() < lose

    fun doubles(): Boolean = random.nextDouble() < twice
}

/** What each account was sent: every key it was told to apply, how often it was asked what a key got, and faults. */
internal class Seen {
    val applied = Shared(emptyMap<String, List<String>>())
    val asked = Shared(0)

    /** How many more answers to an apply are lost before they reach the step. */
    val toLose = Shared(0)

    /** The faults of the run in progress, if any. */
    val faults = Shared<Faults?>(null)

    /** The account [id], in front of which every message is counted and faults are made. */
    fun account(id: String) =
        behaviour<Account, ActorRef<Account>?>(null) { ctx, child, message ->
            val inner = child ?: ctx.spawn("keyed", keyedAccount(id))
            when (message) {
                is Keyed.Apply -> {
                    applied.update { it + (id to it[id].orEmpty() + message.key) }
                    val counted = toLose.modify { left -> (left - 1).coerceAtLeast(0) to (left > 0) }
                    val run = faults.peek()
                    val passed = if (counted || run?.loses() == true) message.copy(reply = Lost) else message
                    inner.tell(passed)
                    // A duplicate is a second delivery with a reply of its own, which nobody is waiting on any more.
                    if (run?.doubles() == true) inner.tell(message.copy(reply = Lost))
                }

                is Keyed.Applied -> {
                    asked.update { count -> count + 1 }
                    inner.tell(message)
                }
            }
            become(inner)
        }
}

/** A one-node cluster with the accounts sharded on it, on a scope of its own, until [close]. */
internal class Branch(seen: Seen) : AutoCloseable {
    /** The scope's journal, which the accounts write. */
    val journal = InMemoryJournal()
    private val done = Promise<Nothing, Unit>()
    private val opened = Promise<Throwable, Sharded<Account>>()
    private val running = surefoldFuture {
        scope {
            journal(journal)
            val port = freePort()
            val gossip =
                Gossiping(probeEvery = 200.milliseconds, ackWithin = 100.milliseconds, formAfter = 200.milliseconds)
            val cluster = cluster(node("bank", port), Discovery.static(Node("", "127.0.0.1", port)), gossip)
            val wire = Keyed.codec(MoveWire, RefusalWire)
            val accounts = cluster.sharding("account", wire, 1.minutes, entity = seen::account)
            eventually(30.seconds) {
                check(cluster.view.members.any { it.status == Status.Up }) { "the bank is not up" }
            }
            opened.succeed(accounts)
            done.await()
        }
    }
        .whenComplete { _, thrown -> thrown?.let(opened::fail) }

    val accounts: Sharded<Account>
        get() = opened.await().fold({ throw IllegalStateException("the bank did not open", it) }, { it })

    /** What [id] holds, folded from its own journal rather than asked of it. */
    fun balance(id: String): Long =
        journal
            .read(PersistenceId("account", id))
            .map { keyedEvents(Moves, Refusals).decode(it.bytes) }
            .filterIsInstance<KeyedEvent.Decided<Move>>()
            .fold(0L) { balance, decided -> balance.moved(decided.event) }

    override fun close() {
        done.succeed(Unit)
        running.join()
    }
}

/** Throws, as a node dying would, instead of the append numbered [dieAt]; the appends before it are kept. */
internal class Crashing(private val inner: Journal, private val dieAt: Int) : Journal by inner {
    private var appends = 0

    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>): Either<JournalConflict, Long> {
        check(++appends != dieAt) { "the node died before append $dieAt" }
        return inner.append(id, expected, events)
    }

    override fun read(id: PersistenceId, from: Long): List<StoredEvent> = inner.read(id, from)
}
