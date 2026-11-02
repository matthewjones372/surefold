package io.github.matthewjones372.surefold.cluster

import dev.surefold.fail.onLeft
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.withClue
import io.github.matthewjones372.surefold.actor.Journal
import io.github.matthewjones372.surefold.actor.JournalConflict
import io.github.matthewjones372.surefold.actor.JournalPruning
import io.github.matthewjones372.surefold.actor.PersistenceId
import io.github.matthewjones372.surefold.actor.events
import io.github.matthewjones372.surefold.actor.journal.jdbc.JdbcJournal
import io.github.matthewjones372.surefold.actor.remote.Node
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** [journal], keeping each append that another writer beat: two producers running under one id. */
private class Conflicts(private val journal: JdbcJournal) :
    Journal by journal,
    JournalPruning by journal {
    val seen = CopyOnWriteArrayList<JournalConflict>()

    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>) =
        journal.append(id, expected, events).onLeft { seen += it }
}

object ProducersSuite : Suite() {
    init {
        test(
            "two lives of one node, restarted in place, keep separate outboxes, and each one's commands are delivered",
        ) {
            val journal = JdbcJournal(accountsDatabase())
            val ports = List(3) { ServerSocket(0).use(ServerSocket::getLocalPort) }
            val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
            // Every node goes as a crashed one does, so the third's earlier life is still held when it comes back.
            val bank = { i: Int -> Bank("b${i + 1}", ports[i], seeds, journal, Duration.ZERO) }
            val banks = (0 until 3).map(bank).toMutableList()
            val ids = List(20) { "a-$it" }
            fun Bank.up() = cluster.await(1.minutes) { view ->
                view.members.count { it.status == Status.Up } == 3 && view.members.any { cluster.isSelf(it) }
            }
            fun Bank.pay(pence: Int) {
                val payments = accounts.reliable("checkout", durable = true)
                ids.forAll { id -> payments.send(id) { PayInto(pence, it) } shouldBe Unit.right() }
                payments.drain(1.minutes) shouldBe true
            }
            try {
                banks.forAll { it.up() shouldBe true }
                val earlier = banks.removeLast()
                val lives = mutableListOf(earlier.cluster.life)
                earlier.pay(1)
                earlier.close()
                val later = bank(2).also(banks::add)
                later.up() shouldBe true
                lives += later.cluster.life
                later.pay(2)

                ids.forAll { id -> journal.events(accountOf(id), paidPence) shouldBe listOf(1, 2) }
                lives.distinct().size shouldBe 2
                // Each life kept its 20 payments and their confirmations, and nothing is kept under the id alone.
                lives.forAll { life ->
                    journal.read(PersistenceId("surefold-producer", "account-checkout-$life")).size shouldBe 40
                }
                journal.read(PersistenceId("surefold-producer", "account-checkout")).shouldBeEmpty()
            } finally {
                banks.forEach(Bank::close)
            }
        }

        test("on three nodes, the registry lists each node's durable producer with the life that runs it") {
            val journal = JdbcJournal(accountsDatabase())
            val ports = List(3) { ServerSocket(0).use(ServerSocket::getLocalPort) }
            val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
            val banks = ports.mapIndexed { i, port -> Bank("b${i + 1}", port, seeds, journal, Duration.ZERO) }
            try {
                banks.forAll { bank ->
                    bank.cluster.await(1.minutes) { view ->
                        view.members.count { it.status == Status.Up } == 3
                    } shouldBe true
                }
                banks.forEach { val _ = it.accounts.reliable("checkout", durable = true) }

                val lives = banks.map { it.cluster.life }
                val expected = lives.associate { life -> "account-checkout-$life" to Listed("account", life, life) }
                val oldest = checkNotNull(Placement.oldest(banks.first().cluster.view.members))
                val registry = checkNotNull(banks.single { it.cluster.self == oldest }.cluster.producers)
                withClue({ "the registry lists ${registry.listed}" }) {
                    registry.await(30.seconds) { it == expected } shouldBe true
                }
            } finally {
                banks.forEach(Bank::close)
            }
        }

        test(
            "a crashed node's commands are delivered, though the node that resumed them crashes straight after",
        ) {
            val journal = Conflicts(JdbcJournal(accountsDatabase()))
            val ports = List(4) { ServerSocket(0).use(ServerSocket::getLocalPort) }
            val seeds = Discovery.static(*ports.take(3).map { Node("", "127.0.0.1", it) }.toTypedArray())
            // Accounts run only on a ledger, and none is up until both crashes, so nothing is confirmed before them. A
            // side of one stays, so the last of the three goes on alone.
            val bank = { i: Int ->
                val downing = Downing.staticQuorum(1, stableAfter = 3.seconds)
                Bank("b${i + 1}", ports[i], seeds, journal, Duration.ZERO, "ledger", ledger = i == 3, downing = downing)
            }
            val banks = (0 until 3).map(bank).toMutableList()
            val ids = List(20) { "a-$it" }
            try {
                banks.forAll { b ->
                    b.cluster.await(1.minutes) { view ->
                        view.members.count { it.status == Status.Up } == 3
                    } shouldBe true
                }
                val opened = banks.mapIndexed { i, b ->
                    val payments = b.accounts.reliable("checkout", durable = true)
                    payments to ids.forAll { id -> payments.send(id) { PayInto(i + 1, it) } shouldBe Unit.right() }
                }
                val payments = opened.map { it.first }
                opened.forAll { it.second }
                val oldest = checkNotNull(Placement.oldest(banks.first().cluster.view.members))
                val resumer = banks.single { it.cluster.self == oldest }
                val (stays, crashing) = banks - resumer
                fun outbox(bank: Bank) = "account-checkout-${bank.cluster.life}"
                val outboxes = banks.associateWith(::outbox)

                crashing.close()
                val resumed = checkNotNull(resumer.cluster.producers)
                resumed.await(30.seconds) { listed ->
                    listed?.get(outbox(crashing))?.runner == resumer.cluster.life
                } shouldBe true
                resumer.close()
                banks += bank(3)

                payments[banks.indexOf(stays)].drain(1.minutes) shouldBe true
                val registry = checkNotNull(stays.cluster.producers)
                withClue({ "the registry lists ${registry.listed}" }) {
                    registry.await(30.seconds) { it?.keys == setOf(outbox(stays)) } shouldBe true
                }
                ids.forAll { id -> journal.events(accountOf(id), paidPence).sorted() shouldBe listOf(1, 2, 3) }
                journal.seen.shouldBeEmpty()
                // A retired outbox keeps only its newest event, which the journal needs to check the next append.
                listOf(resumer, crashing).forAll { gone ->
                    journal.read(PersistenceId("surefold-producer", outboxes.getValue(gone))).size shouldBe 1
                }
            } finally {
                banks.forEach(Bank::close)
            }
        }
    }
}
