package dev.surefold.cluster

import dev.surefold.actor.Delivered
import java.net.BindException
import dev.surefold.junit.withClue
import dev.surefold.junit.shouldThrow
import dev.surefold.actor.remote.freePort
import dev.surefold.actor.InMemoryJournal
import dev.surefold.actor.Delivery
import dev.surefold.actor.EventCodec
import dev.surefold.actor.Journal
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.delivered
import dev.surefold.actor.events
import dev.surefold.actor.journal
import dev.surefold.actor.journal.jdbc.JdbcJournal
import dev.surefold.actor.journal.jdbc.Postgres
import dev.surefold.actor.persistent
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.remote.delivery
import dev.surefold.actor.remote.node
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeGreaterThan
import dev.surefold.metrics
import dev.surefold.scope
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Pays [pence] into an account, sent reliably. */
internal data class PayInto(val pence: Int, override val delivery: Delivery) : Delivered {
    override fun redeliver(delivery: Delivery) = copy(delivery = delivery)
}

internal val payIntoCodec = object : MessageCodec<PayInto> {
    override fun write(message: PayInto, out: WireOut) {
        out.int(message.pence)
        out.delivery(message.delivery)
    }

    override fun read(input: WireIn): PayInto = PayInto(input.int(), input.delivery())
}

internal val paidPence = object : EventCodec<Int> {
    override fun encode(event: Int): ByteArray = event.toString().toByteArray()

    override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
}

internal fun accountOf(id: String) = PersistenceId("account", id)

/** An account remembered by what was paid into it, which confirms each payment and drops those it has had. */
internal fun account(id: String) = delivered(
    persistent<PayInto, Int, Int>(
        id = accountOf(id),
        empty = 0,
        codec = paidPence,
        command = { _, _, pay -> persist(pay.pence) },
        event = { total, paid -> total + paid },
    ),
)

/** Probes calm enough that 200 entities writing to one database never make two live nodes miss each other. */
internal val calm =
    Gossiping(probeEvery = 500.milliseconds, ackWithin = 250.milliseconds, formAfter = 1_000.milliseconds)

internal fun accountsDatabase(): DataSource = Postgres.fresh()

/**
 * A node with the accounts sharded on it and [journal] as its scope's, on a thread of its own, until [close]. With a
 * [ledger] role, accounts run only on members that hold it, and this one holds it if [ledger] is true. If it cannot
 * open, as when another socket holds [port], asking for its [cluster] or [accounts] throws why.
 */
internal class Bank(
    val name: String,
    port: Int,
    seeds: Discovery,
    journal: Journal,
    leaveWithin: Duration,
    private val role: String? = null,
    ledger: Boolean = false,
    private val downing: Downing = Downing.keepMajority(stableAfter = 3.seconds),
) : AutoCloseable {
    private val roles = if (ledger) setOfNotNull(role) else emptySet()
    private val done = CountDownLatch(1)
    private val ready = CountDownLatch(1)
    private val opened = AtomicReference<Pair<Cluster, Sharded<PayInto>>>()
    private val failed = AtomicReference<Throwable>()
    val measured = NodeMetrics()
    private val thread = Thread.ofPlatform()
        .uncaughtExceptionHandler { _, thrown ->
            failed.set(thrown)
            ready.countDown()
        }
        .start { metrics.locally(measured) { open(port, seeds, journal, leaveWithin) } }

    private fun open(port: Int, seeds: Discovery, journal: Journal, leaveWithin: Duration) {
        scope {
            journal(journal)
            val cluster = cluster(node(name, port), seeds, calm, downing, leaveWithin, roles)
            val accounts =
                cluster.sharding("account", payIntoCodec, passivateAfter = 1.minutes, role = role, entity = ::account)
            opened.set(cluster to accounts)
            ready.countDown()
            done.await()
        }
    }

    val cluster: Cluster get() = opened().first

    val accounts: Sharded<PayInto> get() = opened().second

    private fun opened(): Pair<Cluster, Sharded<PayInto>> {
        ready.await()
        failed.get()?.let { throw IllegalStateException("$name did not open", it) }
        return opened.get()
    }

    override fun close() {
        done.countDown()
        thread.join()
    }
}

object ReliableSuite : Suite() {
    init {
        test("payments to 200 accounts while a node crashes are each applied exactly once, in order") {
            val journal = JdbcJournal(accountsDatabase())
            val ports = List(3) { freePort() }
            val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
            // The third goes as a crashed node does, without leaving.
            val banks = ports.mapIndexed { i, port ->
                Bank("b${i + 1}", port, seeds, journal, if (i == 2) Duration.ZERO else 30.seconds)
            }
            try {
                banks.forAll { bank ->
                    bank.cluster.await(1.minutes) { view ->
                        view.members.count { it.status == Status.Up } == 3
                    } shouldBe true
                }
                val (sender, stays, crashing) = banks
                val payments = sender.accounts.reliable("checkout", resendAfter = 200.milliseconds, keep = 1_000)
                val accounts = List(200) { "a-$it" }

                fun pay(pence: Int) = accounts.forAll { id ->
                    payments.send(id) { PayInto(pence, it) } shouldBe
                        Unit.right()
                }

                (1..2).forAll { pay(it) }
                payments.drain(1.minutes) shouldBe true
                // The third node stops without leaving, with the third payments on their way to its accounts: what was
                // in flight to it is lost, and what it wrote may not have been confirmed.
                pay(3)
                crashing.close()
                (4..5).forAll { pay(it) }
                listOf(sender, stays).forAll { bank ->
                    bank.cluster.await(1.minutes) { view ->
                        view.members.none { it.node.name == crashing.name }
                    } shouldBe true
                }

                payments.drain(40.seconds) shouldBe true
                accounts.forAll { id ->
                    journal.events(accountOf(id), paidPence) shouldBe (1..5).toList()
                }

                // What the survivors gauge (spec 0081): every shard and every account between them, nothing kept for a
                // shard without an owner, nothing unconfirmed, and the crash made the producer send again.
                val survivors = listOf(sender, stays)
                fun sum(name: String) =
                    survivors.sumOf { it.measured.gauge(name, "node" to it.name, "kind" to "account") ?: 0.0 }
                val producer = arrayOf("node" to sender.name, "producer" to "account-checkout")
                sum("surefold.sharding.shards") shouldBe 256.0
                sum("surefold.sharding.entities") shouldBe 200.0
                sum("surefold.sharding.buffered") shouldBe 0.0
                sender.measured.gauge("surefold.delivery.unconfirmed", *producer) shouldBe 0.0
                sender.measured.counter("surefold.delivery.resent", *producer) shouldBeGreaterThan 0.0
            } finally {
                banks.forEach(Bank::close)
            }
        }

        test(
            "payments a node sends just before it closes are each applied once, from wherever their accounts run",
        ) {
            val journal = JdbcJournal(accountsDatabase())
            val ports = List(3) { freePort() }
            val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
            val banks = ports.mapIndexed { i, port -> Bank("b${i + 1}", port, seeds, journal, 30.seconds) }
            try {
                banks.forAll { bank ->
                    bank.cluster.await(1.minutes) { view ->
                        view.members.count { it.status == Status.Up } == 3
                    } shouldBe true
                }
                val closing = banks.last()
                val payments = closing.accounts.reliable("checkout", keep = 1_000)
                val accounts = List(200) { "a-$it" }

                (1..2).forAll { pence ->
                    accounts.forAll { id -> payments.send(id) { PayInto(pence, it) } shouldBe Unit.right() }
                }
                closing.close()

                accounts.forAll { id -> journal.events(accountOf(id), paidPence) shouldBe listOf(1, 2) }
            } finally {
                banks.forEach(Bank::close)
            }
        }

        test(
            "payments a durable producer accepted before its node crashed are each applied once, sent by the registry",
        ) {
            val journal = JdbcJournal(accountsDatabase())
            val ports = List(4) { freePort() }
            val seeds = Discovery.static(*ports.take(3).map { Node("", "127.0.0.1", it) }.toTypedArray())
            // Accounts run only on a ledger, and none is up until the crash: every payment is still unconfirmed then.
            // Each node goes as a crashed one does, without leaving or draining.
            val bank = { i: Int ->
                Bank("b${i + 1}", ports[i], seeds, journal, Duration.ZERO, "ledger", ledger = i == 3)
            }
            val banks = (0 until 3).map(bank).toMutableList()
            try {
                banks.forAll { b ->
                    b.cluster.await(1.minutes) { view ->
                        view.members.count { it.status == Status.Up } == 3
                    } shouldBe true
                }
                val (crashing) = banks
                val accepted = crashing.accounts.reliable("checkout", keep = 1_000, durable = true)
                val accounts = List(200) { "a-$it" }
                (1..2).forAll { pence ->
                    accounts.forAll { id -> accepted.send(id) { PayInto(pence, it) } shouldBe Unit.right() }
                }

                val kept = "account-checkout-${crashing.cluster.life}"
                crashing.close()
                // 400 kept and none confirmed: what the registry resumes, it has only from the journal.
                journal.read(PersistenceId("surefold-producer", kept)).size shouldBe 400
                banks += bank(3)
                val survivors = banks.drop(1)
                survivors.forAll { b ->
                    b.cluster.await(1.minutes) { view ->
                        view.members.none { it.node.name == crashing.name }
                    } shouldBe true
                }
                val oldest = checkNotNull(Placement.oldest(survivors.first().cluster.view.members))
                val registry = checkNotNull(survivors.single { it.cluster.self == oldest }.cluster.producers)

                // Resumed on the registry's node, and retired once every payment it kept is confirmed.
                registry.await(30.seconds) { listed -> listed != null && kept !in listed } shouldBe true
                accounts.forAll { id -> journal.events(accountOf(id), paidPence) shouldBe listOf(1, 2) }
            } finally {
                banks.forEach(Bank::close)
            }
        }

        test("a bank whose port another socket holds says why it did not open, rather than keeping its callers waiting") {
            ServerSocket(freePort()).use { held ->
                Bank("held", held.localPort, Discovery.static(), InMemoryJournal(), Duration.ZERO).use { bank ->
                    shouldThrow<IllegalStateException>({ bank.cluster }) { thrown ->
                        withClue({ "it was given $thrown" }) { (thrown.cause is BindException) shouldBe true }
                    }
                }
            }
        }
    }
}
