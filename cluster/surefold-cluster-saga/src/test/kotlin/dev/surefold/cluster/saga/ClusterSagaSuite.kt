package dev.surefold.cluster.saga

import dev.surefold.Clock
import dev.surefold.Promise
import dev.surefold.Settled
import dev.surefold.Shared
import dev.surefold.TestClock
import dev.surefold.actor.InMemoryJournal
import dev.surefold.actor.Journal
import dev.surefold.actor.StateCodec
import dev.surefold.actor.journal
import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.freePort
import dev.surefold.actor.remote.node
import dev.surefold.awaitValue
import dev.surefold.clock
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.Discovery
import dev.surefold.cluster.Downing
import dev.surefold.cluster.Gossiping
import dev.surefold.cluster.Status
import dev.surefold.cluster.cluster
import dev.surefold.fail.fold
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import dev.surefold.scope
import dev.surefold.surefoldFuture
import dev.surefold.test.eventually
import java.time.Instant
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

    /** The time each run of a body read, by node. */
    val read = Shared(emptyMap<String, Instant>())

    fun move(key: String, pence: Long): Long {
        applied.update { if (key in it) it else it + (key to pence) }
        return applied.peek().getValue(key)
    }

    fun find(key: String): Settled<Long> = applied.peek()[key]?.let { Settled.Happened(it) } ?: Settled.DidNot
}

/** Holds the first run that reaches [at], on whichever node it runs, for as long as that node lives. */
private class Gate(private val at: String?) {
    val reached = Promise<Nothing, String>()

    fun pass(point: String, node: String) {
        if (point == at && reached.succeed(node)) Promise<Nothing, Unit>().await()
    }
}

/** Over [LIMIT] pence the transfer is refused once the debit is made, so the debit is undone. */
private const val LIMIT = 1_000L

private fun transfer(bank: Ledger, gate: Gate, node: String) = durableSaga("transfer", Amount, Words, Words) { pence ->
    val at = now()
    bank.read.update { it + (node to at) }
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

private val calm = Gossiping(probeEvery = 500.milliseconds, ackWithin = 250.milliseconds, formAfter = 1.seconds)

/** A node running the transfer saga, on a scope of its own, until [close]. */
private class Teller(
    val name: String,
    port: Int,
    seeds: Discovery,
    journal: Journal,
    bank: Ledger,
    gate: Gate,
    time: Clock,
) : AutoCloseable {
    private val done = Promise<Nothing, Unit>()
    private val opened = Promise<Throwable, Pair<Cluster, Sagas<Long, String, String>>>()
    private val running = surefoldFuture { opening(port, seeds, journal, bank, gate, time) }
        .whenComplete { _, thrown -> thrown?.let(opened::fail) }

    private fun opening(port: Int, seeds: Discovery, journal: Journal, bank: Ledger, gate: Gate, time: Clock) {
        scope {
            journal(journal)
            // Every node goes as a crashed one does, without leaving.
            val downing = Downing.keepMajority(stableAfter = 2.seconds)
            val cluster = cluster(node(name, port), seeds, calm, downing, leaveWithin = Duration.ZERO)
            // Only the sagas read [time]: gossip on a clock that never moves would never form the cluster.
            opened.succeed(cluster to clock.locally(time) { transfer(bank, gate, name).on(cluster) })
            done.await()
        }
    }

    val cluster: Cluster get() = opened().first

    val sagas: Sagas<Long, String, String> get() = opened().second

    private fun opened() = opened.await().fold({ throw IllegalStateException("$name did not open", it) }, { it })

    /** Stops the node as a crash does: its actors, and the saga body running in one, end with its scope. */
    override fun close() {
        done.succeed(Unit)
        running.join()
    }
}

/** Three tellers on one shared journal and bank, all `Up`, handed to [test] and closed after it. */
private fun tellers(bank: Ledger, gate: Gate, time: Clock = clock.get(), test: (List<Teller>) -> Unit) {
    val journal = InMemoryJournal()
    val ports = List(3) { freePort() }
    val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
    val tellers = ports.mapIndexed { i, port -> Teller("t${i + 1}", port, seeds, journal, bank, gate, time) }
    try {
        tellers.forEach { teller ->
            eventually(1.minutes) {
                val up = teller.cluster.view.members.count { it.status == Status.Up }
                check(up == 3) { "${teller.name} sees $up members up" }
            }
        }
        test(tellers)
    } finally {
        tellers.forEach(Teller::close)
    }
}

/** What [id] ends as, asked of [teller] until it is no longer running. */
private fun ending(teller: Teller, id: String): Durable.Outcome<String, String> = eventually(45.seconds) {
    teller.sagas.await(id, within = 2.seconds).also { check(it != Durable.Outcome.Running) { "$id is still running" } }
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

        test("a saga's body reads the clock bound where its node called on, not the runner's") {
            val bank = Ledger()
            val at = Instant.parse("2026-11-04T12:00:00Z")
            tellers(bank, Gate(null), TestClock(at)) { tellers ->
                tellers.first().sagas.start("t", 400) shouldBe Unit.right()

                ending(tellers.first(), "t") shouldBe Durable.Outcome.Done("moved 400")
                bank.read.peek().values.toSet() shouldBe setOf(at)
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
                val dying = checkNotNull(gate.reached.awaitValue(30.seconds)) { "no run reached ${gap.at}" }
                val (killed, survivors) = tellers.partition { it.name == dying }
                killed.single().close()

                // Nobody asks: the waker alone resumes the saga elsewhere.
                eventually(45.seconds) {
                    check(ledger.applied.peek() == gap.applied) { "the ledger holds ${ledger.applied.peek()}" }
                }
                survivors.forAll { survivor ->
                    withClue("asked of ${survivor.name}, after ${killed.single().name} was killed") {
                        ending(survivor, "t") shouldBe gap.outcome
                    }
                }
            }
        }
    }
}
