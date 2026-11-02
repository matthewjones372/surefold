package io.github.matthewjones372.surefold.cluster

import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeGreaterThan
import dev.surefold.junit.shouldBeLessThanOrEqual
import dev.surefold.junit.shouldContainAll
import dev.surefold.junit.withClue
import dev.surefold.scope
import io.github.matthewjones372.surefold.actor.EventCodec
import io.github.matthewjones372.surefold.actor.InMemoryJournal
import io.github.matthewjones372.surefold.actor.InMemoryOffsets
import io.github.matthewjones372.surefold.actor.PersistenceId
import io.github.matthewjones372.surefold.actor.Slices
import io.github.matthewjones372.surefold.actor.projection.Projection
import io.github.matthewjones372.surefold.actor.projection.mapFollowed
import io.github.matthewjones372.surefold.actor.projection.runProjecting
import io.github.matthewjones372.surefold.actor.remote.Node
import io.github.matthewjones372.surefold.actor.remote.node
import io.github.matthewjones372.surefold.stream.Forks
import io.github.matthewjones372.surefold.stream.start
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

private const val IDS = 1_000
private const val EACH = 1_000
private const val PARTITIONS = 8

private val paid = object : EventCodec<Int> {
    override fun encode(event: Int): ByteArray = "$event".toByteArray()

    override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
}

private val quick =
    Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 1_000.milliseconds)

/** Spec 0106: a read model's partitions spread over the cluster, each moving when its member leaves. */
object SpreadProjectionSuite : Suite() {
    init {
        test(
            "eight partitions over three members follow a million events, and a member's partitions resume elsewhere",
        ) {
            Spread().run()
        }
    }
}

/** One run of the read model: its journal, its offsets and what its workers handled, new for each test. */
private class Spread {

    private val journal = InMemoryJournal()
    private val offsets = InMemoryOffsets()

    // Every event handled, by (id, sequence), and in the order each id's were handled.
    private val handled = AtomicInteger()
    private val times = ConcurrentHashMap<Pair<PersistenceId, Long>, AtomicInteger>()
    private val order = ConcurrentHashMap<PersistenceId, ConcurrentLinkedQueue<Long>>()

    // Which member started each worker, each time one started.
    private val started = ConcurrentLinkedQueue<Pair<Int, String>>()

    /** A member running the read model's workers, on a scope and a thread of its own, until [close]. */
    private inner class Member(val name: String, port: Int, seeds: Discovery) : AutoCloseable {
        private val done = CountDownLatch(1)
        private val ready = CountDownLatch(1)
        private val opened = AtomicReference<Cluster>()
        private val thread = Thread.ofPlatform().start {
            scope {
                val cluster = cluster(node(name, port), seeds, quick)
                cluster.spread("totals", PARTITIONS) { k ->
                    Projection.worker {
                        started += k to name
                        Projection.partitioned(journal, "till", paid, offsets, "totals", k, PARTITIONS)
                            .mapFollowed { event ->
                                times.computeIfAbsent(event.id to event.sequence) { AtomicInteger() }.incrementAndGet()
                                order.computeIfAbsent(event.id) { ConcurrentLinkedQueue() } += event.sequence
                                handled.incrementAndGet()
                            }
                            .runProjecting()
                            .start(Forks())
                    }
                }
                opened.set(cluster)
                ready.countDown()
                done.await()
            }
        }

        val cluster: Cluster
            get() {
                ready.await()
                return opened.get()
            }

        override fun close() {
            done.countDown()
            thread.join()
        }
    }

    fun run() {
        val ids = (1..IDS).map { PersistenceId("till", "t-$it") }
        ids.forEach { id -> journal.append(id, 0, (1..EACH).map { "$it".toByteArray() }) }
        val total = IDS * EACH

        val ports = List(3) { ServerSocket(0).use(ServerSocket::getLocalPort) }
        val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
        val members = ports.mapIndexed { i, port -> Member("n${i + 1}", port, seeds) }.toMutableList()
        try {
            members.forAll { member ->
                member.cluster.await(1.minutes) { view -> view.members.count { it.status == Status.Up } == 3 } shouldBe
                    true
            }
            // Workers move as members join; once all three are up, they settle as even as they go.
            await { started.toList().toMap().values.groupingBy { it }.eachCount().values.sorted() == listOf(2, 3, 3) }
            await { handled.get() >= total / 2 }
            // Where each worker runs now: the last start of each, since workers move as members join.
            val startsBefore = started.toList()
            val placed = startsBefore.toMap()
            withClue("workers spread over the members: $placed") {
                placed.values.toSet().size shouldBeGreaterThan 1
            }

            val leaving = members.first { member -> member.name in placed.values }
            val moved = placed.filterValues { it == leaving.name }.keys
            leaving.close()
            members -= leaving
            await { handled.get() >= total && times.size == total }

            withClue("every event is handled") { times.size shouldBe total }
            val restarted = started.toList().drop(startsBefore.size)
            withClue("the leaving member's partitions started elsewhere: $restarted") {
                restarted.map { it.first }.toSet() shouldContainAll moved
                restarted.none { it.second == leaving.name } shouldBe true
            }
            // Only a moved partition can see an event twice: those it handled and had not yet saved when it stopped.
            val twice = times.filterValues { it.get() > 1 }.keys
            println("placed $placed; ${leaving.name} left; restarted $restarted; ${twice.size} handled twice")
            withClue("${twice.size} events handled twice") {
                twice.all { (id, _) -> partitionOf(id) in moved } shouldBe true
                twice.size shouldBeLessThanOrEqual moved.size * 256
            }
            ids.forAll { id ->
                val first = order.getValue(id).distinct()
                withClue(id) { first shouldBe (1L..EACH).toList() }
            }
        } finally {
            members.forEach(Member::close)
        }
    }

    private fun partitionOf(id: PersistenceId): Int = (0 until PARTITIONS).single {
        Slices.of(id) in Projection.slices(it, PARTITIONS)
    }

    private fun await(done: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + 3.minutes
        while (!done()) {
            check(deadline.hasNotPassedNow()) { "waited 3 minutes: ${handled.get()} handled, ${times.size} distinct" }
            Thread.sleep(50)
        }
    }
}
