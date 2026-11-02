package io.github.matthewjones372.surefold.cluster

import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldNotBe
import dev.surefold.junit.withClue
import dev.surefold.scope
import io.github.matthewjones372.surefold.actor.EventCodec
import io.github.matthewjones372.surefold.actor.Journal
import io.github.matthewjones372.surefold.actor.PersistenceId
import io.github.matthewjones372.surefold.actor.Reply
import io.github.matthewjones372.surefold.actor.SnapshotStore
import io.github.matthewjones372.surefold.actor.StateCodec
import io.github.matthewjones372.surefold.actor.StoredEvent
import io.github.matthewjones372.surefold.actor.ask
import io.github.matthewjones372.surefold.actor.every
import io.github.matthewjones372.surefold.actor.journal
import io.github.matthewjones372.surefold.actor.journal.jdbc.JdbcJournal
import io.github.matthewjones372.surefold.actor.journal.jdbc.JdbcSnapshots
import io.github.matthewjones372.surefold.actor.journal.jdbc.Postgres
import io.github.matthewjones372.surefold.actor.persistent
import io.github.matthewjones372.surefold.actor.remote.Codecs
import io.github.matthewjones372.surefold.actor.remote.MessageCodec
import io.github.matthewjones372.surefold.actor.remote.Node
import io.github.matthewjones372.surefold.actor.remote.WireIn
import io.github.matthewjones372.surefold.actor.remote.WireOut
import io.github.matthewjones372.surefold.actor.remote.node
import io.github.matthewjones372.surefold.actor.snapshots
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private sealed interface Jar

/** Puts [pence] in the jar, and answers what is in it once that is written. */
private data class Put(val pence: Int, val reply: Reply<Int>) : Jar

/** Answers the node the jar runs on, and what is in it. */
private data class Count(val reply: Reply<String>) : Jar

private val jarCodec = object : MessageCodec<Jar> {
    override fun write(message: Jar, out: WireOut) = when (message) {
        is Put -> {
            out.int(1)
            out.int(message.pence)
            out.reply(message.reply, Codecs.int)
        }

        is Count -> {
            out.int(2)
            out.reply(message.reply, Codecs.string)
        }
    }

    override fun read(input: WireIn): Jar = when (val tag = input.int()) {
        1 -> Put(input.int(), input.reply(Codecs.int))
        2 -> Count(input.reply(Codecs.string))
        else -> error("no jar message has the tag $tag")
    }
}

private val pence = object : EventCodec<Int> {
    override fun encode(event: Int): ByteArray = event.toString().toByteArray()

    override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
}

private val total = object : StateCodec<Int> {
    override fun encode(state: Int): ByteArray = state.toString().toByteArray()

    override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
}

/** A jar of coins remembered by what was put in it, with a snapshot after every fourth coin. */
private fun jar(on: String, id: String) = persistent<Jar, Int, Int>(
    id = PersistenceId("jar", id),
    empty = 0,
    codec = pence,
    snapshots = every(4, total),
    command = { _, total, command ->
        when (command) {
            is Put -> persist(command.pence).then { command.reply(it) }
            is Count -> none().then { command.reply("$on:$total") }
        }
    },
    event = { total, put -> total + put },
)

private val quick =
    Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 1_000.milliseconds)

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** A fresh Postgres, with the table the journal's jar ships. */
private fun sharedDatabase(): DataSource = Postgres.fresh()

/** A journal that remembers where each read of it began. */
private class Reads(private val kept: Journal) : Journal by kept {
    val from = ConcurrentLinkedQueue<Long>()

    override fun read(id: PersistenceId, from: Long): List<StoredEvent> = kept.read(id, from).also { this.from += from }
}

/**
 * A node with the jars sharded on it and [journal] and [snapshots] as its scope's, on a thread of its own, until
 * [close].
 */
private class Shop(val name: String, port: Int, seeds: Discovery, journal: Journal, snapshots: SnapshotStore) :
    AutoCloseable {
    private val done = CountDownLatch(1)
    private val ready = CountDownLatch(1)
    private val opened = AtomicReference<Pair<Cluster, Sharded<Jar>>>()
    private val thread = Thread.ofPlatform().start {
        scope {
            journal(journal)
            snapshots(snapshots)
            val cluster = cluster(node(name, port), seeds, quick)
            opened.set(cluster to cluster.sharding("jar", jarCodec, passivateAfter = 1.minutes) { jar(name, it) })
            ready.countDown()
            done.await()
        }
    }

    val cluster: Cluster
        get() {
            ready.await()
            return opened.get().first
        }

    val jars: Sharded<Jar>
        get() {
            ready.await()
            return opened.get().second
        }

    override fun close() {
        done.countDown()
        thread.join()
    }
}

object RememberedSuite : Suite() {
    init {
        test(
            "a sharded persistent entity answers from its next owner with every event it had, from its snapshot",
        ) {
            val database = sharedDatabase()
            val journal = Reads(JdbcJournal(database))
            val snapshots = JdbcSnapshots(database)
            val ports = List(3) { freePort() }
            val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
            val shops = ports.mapIndexed { i, port -> Shop("s${i + 1}", port, seeds, journal, snapshots) }
            try {
                shops.forAll { shop ->
                    val up = shop.cluster.await(1.minutes) { view ->
                        view.members.count { it.status == Status.Up } == 3
                    }
                    withClue("${shop.name} sees 3 Up") { up shouldBe true }
                }
                val shard = Placement.shardOf("j-1", Sharding.SHARDS)
                val owner = checkNotNull(Placement.owner("jar", shard, shops.first().cluster.view.members)).name
                val stays = shops.filter { it.name != owner }
                val jar = stays.first().jars.entity("j-1")

                (1..10).forAll { put ->
                    jar.ask<Jar, Int>(1.minutes) { Put(put, it) } shouldBe (put * (put + 1) / 2).right()
                }
                jar.ask<Jar, String>(1.minutes) { Count(it) } shouldBe "$owner:55".right()

                shops.single { it.name == owner }.cluster.leave()
                stays.forAll { shop ->
                    shop.cluster.await(1.minutes) { view -> view.members.none { it.node.name == owner } } shouldBe true
                }

                journal.from.clear()
                val recounted = jar.ask<Jar, String>(1.minutes) { Count(it) }.getOrNull()
                withClue("the jar answers from another node, with all 55 pence") {
                    recounted?.substringBefore(':') shouldNotBe owner
                    recounted?.substringAfter(':') shouldBe "55"
                }
                withClue(
                    "the next owner recovers from the snapshot at the eighth coin, and reads the two after it",
                ) {
                    journal.from.toList() shouldBe listOf(9L)
                }
            } finally {
                shops.forEach(Shop::close)
            }
        }
    }
}
