package dev.surefold.bank

import dev.surefold.actor.NotSent
import dev.surefold.actor.journal.jdbc.JdbcJournal
import dev.surefold.actor.journal.jdbc.Postgres
import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.freePort
import dev.surefold.cluster.Discovery
import dev.surefold.cluster.Gossiping
import dev.surefold.fail.Either
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.timeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Brisk gossip, but resends no sooner than a resumed producer's. An entity answers each repeat by sending again,
 * so on a loaded machine a resend that beats the journal's round trip makes more writes: at 300 ms the test stalled.
 */
internal val calm = Settings(
    gossiping = Gossiping(probeEvery = 500.milliseconds, ackWithin = 250.milliseconds, formAfter = 1.seconds),
    resendAfter = 2.seconds,
)

/** Three nodes on one journal, each on its own thread, formed into a cluster before this returns. */
internal fun threeNodes(settings: Settings = calm, ended: (String, Saga) -> Unit = { _, _ -> }): List<BankNode> {
    val journal = JdbcJournal(Postgres.fresh())
    val ports = List(3) { freePort() }
    val seeds = ports.map { Node("", "127.0.0.1", it) }.let { Discovery { it } }
    val nodes = ports.mapIndexed { i, port -> BankNode("n${i + 1}", port, seeds, journal, settings, ended) }
    nodes.forEach { node ->
        check(node.members.await(1.minutes) { it.up.size == 3 }) { "${node.name} never saw all three nodes up" }
    }
    return nodes
}

object CrashSuite : Suite() {
    init {
        test(
            "a thousand transfers across three nodes, one crashed midway, end done or refused and lose no money",
            timeout(120.seconds),
        ) {
            val ended = LinkedBlockingQueue<Pair<String, Phase>>()
            val nodes = threeNodes { id, saga -> ended.put(id to saga.phase) }
            val (n1, n2, n3) = nodes
            try {
                val accounts = List(20) { "a-$it" }
                accounts.forAll { n1.open(it, 1_000) shouldBe 1_000L.right() }
                val random = Random(94)
                val transfers = List(1_000) { i ->
                    val (from, to) = accounts.shuffled(random).take(2)
                    Triple("t-$i", from to to, random.nextLong(1, 400))
                }

                // One sender per node, all at once, so each node has work in flight when n3 goes halfway through its
                // share.
                val halfway = CountDownLatch(1)
                val sent = ConcurrentLinkedQueue<Either<NotSent, Unit>>()
                val senders = nodes.mapIndexed { n, node ->
                    Thread.ofPlatform().start {
                        transfers.filterIndexed { i, _ -> i % 3 == n }.forEachIndexed { i, (id, fromTo, pence) ->
                            if (node == n3 && i == 150) halfway.countDown()
                            val via = if (node == n3 && i >= 150) n1 else node
                            sent += via.transfer(id, fromTo.first, fromTo.second, pence)
                        }
                    }
                }
                halfway.await()
                n3.close()
                senders.forEach(Thread::join)
                sent.toList() shouldBe List(transfers.size) { Unit.right() }

                listOf(n1, n2).forAll { node ->
                    node.members.await(1.minutes) { seen -> seen.members.keys.none { it.name == "n3" } } shouldBe true
                }
                // Each transfer is heard to end at least once; a crash may repeat one.
                val outcomes = generateSequence { ended.poll(30, TimeUnit.SECONDS) }
                    .scan(emptyMap<String, Phase>()) { heard, (id, phase) -> heard + (id to phase) }
                    .firstOrNull { it.size == transfers.size }
                outcomes?.keys shouldBe transfers.map { it.first }.toSet()
                outcomes?.values?.toSet() shouldBe setOf(Phase.Done, Phase.Refused)
                accounts.sumOf { n1.statement(it).getOrNull()?.balance ?: 0 } shouldBe 20_000L
            } finally {
                nodes.forEach(BankNode::close)
            }
        }
    }
}
