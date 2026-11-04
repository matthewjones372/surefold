package dev.surefold.cluster.saga

import dev.surefold.Settled
import dev.surefold.Shared
import dev.surefold.actor.InMemoryJournal
import dev.surefold.actor.Journal
import dev.surefold.actor.JournalConflict
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.StateCodec
import dev.surefold.actor.StoredEvent
import dev.surefold.actor.journal
import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.freePort
import dev.surefold.actor.remote.node
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.Discovery
import dev.surefold.cluster.Downing
import dev.surefold.cluster.Gossiping
import dev.surefold.cluster.Status
import dev.surefold.cluster.cluster
import dev.surefold.fail.Either
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import dev.surefold.scope
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private object Words : StateCodec<String> {
    override fun encode(state: String): ByteArray = state.toByteArray()

    override fun decode(bytes: ByteArray): String = String(bytes)
}

private object Amount : StateCodec<Long> {
    override fun encode(state: Long): ByteArray = state.toString().toByteArray()

    override fun decode(bytes: ByteArray): Long = String(bytes).toLong()
}

/** A bank every node of the test reaches, which applies each key once, as a keyed entity would. */
private class Ledger {
    val applied = Shared(emptyMap<String, Long>())

    fun move(key: String, pence: Long): Long {
        applied.update { if (key in it) it else it + (key to pence) }
        return applied.peek().getValue(key)
    }

    fun find(key: String): Settled<Long> = applied.peek()[key]?.let { Settled.Happened(it) } ?: Settled.DidNot
}

/** Holds the first run that reaches [at], on whichever node it runs, until the test lets it go, then ends it. */
private class Gate(private val at: String?) {
    val reached = CompletableFuture<String>()
    val release = CountDownLatch(1)

    fun pass(point: String, node: String) {
        if (point == at && reached.complete(node)) {
            release.await()
            error("$node was killed")
        }
    }
}

/** Over [LIMIT] pence the transfer is refused once the debit is made, so the debit is undone. */
private const val LIMIT = 1_000L

private fun transfer(bank: Ledger, gate: Gate, node: String) = durableSaga("transfer", Amount, Words, Words) { pence ->
    gate.pass("started", node)
    step(
        "debit",
        Amount,
        { key -> bank.move(key, -pence) },
        { key -> bank.find(key) },
        { key, _, _ ->
            gate.pass("undoing", node)
            bank.move(key, pence)
        },
    )
    gate.pass("between", node)
    if (pence > LIMIT) fail("over the limit")
    val credited = step("credit", Amount, { key -> bank.move(key, pence) }, { key -> bank.find(key) })
    "moved $credited"
}

/** The scope's journal, which a killed node no longer reaches: a runner still going there writes nothing. */
private class Mortal(private val inner: Journal) : Journal by inner {
    @Volatile
    var dead = false

    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>): Either<JournalConflict, Long> {
        check(!dead) { "the node is dead" }
        return inner.append(id, expected, events)
    }

    override fun read(id: PersistenceId, from: Long): List<StoredEvent> {
        check(!dead) { "the node is dead" }
        return inner.read(id, from)
    }
}

private val calm = Gossiping(probeEvery = 500.milliseconds, ackWithin = 250.milliseconds, formAfter = 1.seconds)

/** A node running the transfer saga, on a scope and a thread of its own, until [close] or [kill]. */
private class Teller(val name: String, port: Int, seeds: Discovery, shared: Journal, bank: Ledger, gate: Gate) :
    AutoCloseable {
    private val journal = Mortal(shared)
    private val done = CountDownLatch(1)
    private val ready = CountDownLatch(1)
    private val opened = AtomicReference<Pair<Cluster, Sagas<Long, String, String>>>()
    private val failed = AtomicReference<Throwable>()
    private val thread = Thread.ofPlatform()
        .uncaughtExceptionHandler { _, thrown ->
            failed.set(thrown)
            ready.countDown()
        }
        .start {
            scope {
                journal(journal)
                // Every node goes as a crashed one does, without leaving.
                val downing = Downing.keepMajority(stableAfter = 2.seconds)
                val cluster = cluster(node(name, port), seeds, calm, downing, leaveWithin = Duration.ZERO)
                opened.set(cluster to transfer(bank, gate, name).on(cluster))
                ready.countDown()
                done.await()
            }
        }

    val cluster: Cluster get() = opened().first

    val sagas: Sagas<Long, String, String> get() = opened().second

    private fun opened(): Pair<Cluster, Sagas<Long, String, String>> {
        ready.await()
        failed.get()?.let { throw IllegalStateException("$name did not open", it) }
        return opened.get()
    }

    /** Stops the node as a crash would: its journal gone first, then its actors, and nothing said to the others. */
    fun kill() {
        journal.dead = true
        close()
    }

    override fun close() {
        done.countDown()
        thread.join()
    }
}

/** Three tellers on one shared journal and bank, all `Up`, handed to [test] and closed after it. */
private fun tellers(bank: Ledger, gate: Gate, test: (List<Teller>) -> Unit) {
    val journal = InMemoryJournal()
    val ports = List(3) { freePort() }
    val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
    val tellers = ports.mapIndexed { i, port -> Teller("t${i + 1}", port, seeds, journal, bank, gate) }
    try {
        tellers.forAll { teller -> allUp(teller.cluster) shouldBe true }
        test(tellers)
    } finally {
        tellers.forEach(Teller::close)
    }
}

/** Whether [cluster]'s view has all three members `Up` within a minute. */
private fun allUp(cluster: Cluster): Boolean {
    val deadline = System.nanoTime() + 1.minutes.inWholeNanoseconds
    while (cluster.view.members.count { it.status == Status.Up } < 3) {
        if (System.nanoTime() > deadline) return false
        Thread.sleep(50)
    }
    return true
}

/** What [id] ends as, asked of [teller] until it is no longer running. */
private fun ending(teller: Teller, id: String): Durable.Outcome<String, String> {
    val deadline = System.nanoTime() + 45.seconds.inWholeNanoseconds
    var outcome = teller.sagas.await(id, within = 2.seconds)
    while (outcome == Durable.Outcome.Running && System.nanoTime() < deadline) {
        outcome = teller.sagas.await(id, within = 2.seconds)
    }
    return outcome
}

/** Whether [ledger] comes to hold [applied] within 45 seconds. */
private fun settles(ledger: Ledger, applied: Map<String, Long>): Boolean {
    val deadline = System.nanoTime() + 45.seconds.inWholeNanoseconds
    while (ledger.applied.peek() != applied) {
        if (System.nanoTime() > deadline) return false
        Thread.sleep(50)
    }
    return true
}

/** Where a node is killed, the transfer it runs, and how that ends with no crash at all. */
private data class Gap(
    val at: String,
    val pence: Long,
    val outcome: Durable.Outcome<String, String>,
    val applied: Map<String, Long>,
)

private val moved = mapOf("t/debit" to -400L, "t/credit" to 400L)
private val undone = mapOf("t/debit" to -2_000L, "t/debit/undo" to 2_000L)

private val gaps = listOf(
    Gap("started", 400, Durable.Outcome.Done("moved 400"), moved),
    Gap("between", 400, Durable.Outcome.Done("moved 400"), moved),
    Gap("undoing", 2_000, Durable.Outcome.Refused("over the limit"), undone),
)

object ClusterSagaSuite : Suite(concurrent = false) {
    init {
        test("a saga started on one node is awaited from every node, and ends as it would on one node") {
            val bank = Ledger()
            tellers(bank, Gate(null)) { tellers ->
                tellers.first().sagas.start("t", 400) shouldBe Unit.right()

                tellers.forAll { ending(it, "t") shouldBe Durable.Outcome.Done("moved 400") }
                bank.applied.peek() shouldBe moved
            }
        }

        test("starting a saga again with its id runs nothing more, whatever the input") {
            val bank = Ledger()
            tellers(bank, Gate(null)) { tellers ->
                val (first, second) = tellers
                first.sagas.start("t", 400) shouldBe Unit.right()
                second.sagas.start("t", 2_000) shouldBe Unit.right()

                ending(second, "t") shouldBe Durable.Outcome.Done("moved 400")
                bank.applied.peek() shouldBe moved
            }
        }

        test("a saga nobody started is awaited as forgotten") {
            tellers(Ledger(), Gate(null)) { tellers ->
                tellers.last().sagas.await("never", within = 2.seconds) shouldBe Durable.Outcome.Forgotten
            }
        }

        testEach(
            "a saga whose node is killed ends on another as it would have without the crash",
            gaps,
            named = Gap::at,
        ) { gap ->
            val ledger = Ledger()
            val gate = Gate(gap.at)
            tellers(ledger, gate) { tellers ->
                tellers.first().sagas.start("t", gap.pence) shouldBe Unit.right()
                val dying = gate.reached.get(30, TimeUnit.SECONDS)
                val (killed, survivors) = tellers.partition { it.name == dying }
                killed.single().kill()
                gate.release.countDown()

                // Nobody asks: the waker alone resumes the saga elsewhere.
                withClue({ "the ledger holds ${ledger.applied.peek()}" }) { settles(ledger, gap.applied) shouldBe true }
                survivors.forAll { survivor ->
                    withClue("asked of ${survivor.name}, after ${killed.single().name} was killed") {
                        ending(survivor, "t") shouldBe gap.outcome
                    }
                }
            }
        }
    }
}
