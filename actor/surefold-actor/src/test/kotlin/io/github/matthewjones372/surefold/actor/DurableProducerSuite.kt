package io.github.matthewjones372.surefold.actor

import dev.surefold.LogLevel
import dev.surefold.LogLine
import dev.surefold.capturingMetrics
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldBeLessThan
import dev.surefold.junit.shouldBeLessThanOrEqual
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withClue
import dev.surefold.logger
import dev.surefold.scope
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.sql.SQLException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/** Tops a wallet up by [pence], sent durably. */
private data class TopUp(val pence: Long, override val delivery: Delivery) : Delivered {
    override fun redeliver(delivery: Delivery) = copy(delivery = delivery)
}

/** Tops a wallet up, and cannot be sent again with a new delivery. */
private data class OneOff(override val delivery: Delivery) : Delivered

/** A top-up as a durable producer keeps it: the pence and whose it is, with nowhere to confirm to. */
private val topUpCodec = object : EventCodec<TopUp> {
    override fun encode(event: TopUp): ByteArray {
        require(event.pence >= 0) { "a top-up of ${event.pence} is refused" }
        return with(event.delivery) { "${event.pence}|$producer|$to|$sequence" }.toByteArray()
    }

    override fun decode(bytes: ByteArray): TopUp {
        val (pence, producer, to, sequence) = String(bytes).split("|")
        return TopUp(pence.toLong(), Delivery(producer, to, sequence.toLong(), Delivery.NoOne))
    }
}

private val pence = object : EventCodec<Long> {
    override fun encode(event: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(event).array()

    override fun decode(bytes: ByteArray): Long = ByteBuffer.wrap(bytes).long
}

private fun walletOf(id: String) = PersistenceId("wallet", id)

private fun wallet(id: String) = delivered(
    persistent<TopUp, Long, Long>(
        id = walletOf(id),
        empty = 0,
        codec = pence,
        command = { _, _, topUp -> persist(topUp.pence) },
        event = { balance, paid -> balance + paid },
    ),
)

/** Never answers: a node that has gone. */
private val nowhere = object : ActorRef<TopUp> {
    override val address = Address("test", "/nowhere", 0)

    override fun tell(message: TopUp) = Unit
}

/** A journal whose producers' appends throw for the first [failures], as a database that is down does. */
private class Flaky(failures: Int, private val kept: Journal = InMemoryJournal()) : Journal {
    private val left = AtomicInteger(failures)

    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>) =
        if (id.kind == "surefold-producer" && left.getAndDecrement() > 0) {
            throw SQLException("the database is down")
        } else {
            kept.append(id, expected, events)
        }

    override fun read(id: PersistenceId, from: Long) = kept.read(id, from)
}

/** A journal whose producers' appends throw until the test brings it [back]. */
private class Down : Journal {
    private val kept = InMemoryJournal()
    private val outage = CountDownLatch(1)

    fun back() = outage.countDown()

    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>) =
        if (id.kind == "surefold-producer" && outage.count > 0) {
            throw SQLException("the database is down")
        } else {
            kept.append(id, expected, events)
        }

    override fun read(id: PersistenceId, from: Long) = kept.read(id, from)
}

object DurableProducerSuite : Suite() {
    private val ids = (0 until 5).map { "w-$it" }

    init {
        test(
            "a producer started again under its id sends every unconfirmed command once, and numbers on after them",
        ) {
            testActors {
                val wallets = ids.associateWith { spawn(it, wallet(it)) }
                val crashed = durableProducer("till", topUpCodec) { nowhere }
                for (n in 0 until 50) crashed.send(ids[n % 5]) { TopUp(n.toLong(), it) }

                val again = durableProducer("till", topUpCodec) { id -> wallets.getValue(id) }
                again.drain(Duration.ZERO) shouldBe true
                val _ = again.send("w-0") { TopUp(1_000, it) }

                ids.forEachIndexed { w, id ->
                    val sent = (0 until 50).filter { it % 5 == w }.map(Int::toLong)
                    journal.events(walletOf(id), pence) shouldContainExactly if (w == 0) sent + 1_000 else sent
                }
                wallets.getValue("w-0").state.delivered shouldBe mapOf("till" to 11L)
            }
        }

        test("across databases the outbox sits in its producer's own database, and a restart finds it there") {
            val databases = listOf("db-a" to InMemoryJournal(), "db-b" to InMemoryJournal())
            val sharded = ShardedJournal(databases)
            testActors(journal = sharded) {
                val wallets = ids.associateWith { spawn(it, wallet(it)) }
                val crashed = durableProducer("till", topUpCodec) { nowhere }
                for (n in 0 until 10) crashed.send(ids[n % 5]) { TopUp(n.toLong(), it) }

                val outbox = PersistenceId("surefold-producer", "till")
                databases.forEach { (name, kept) ->
                    kept.read(outbox).isNotEmpty() shouldBe
                        (name == sharded.database(outbox))
                }

                val again = durableProducer("till", topUpCodec) { id -> wallets.getValue(id) }
                again.drain(Duration.ZERO) shouldBe true
                ids.sumOf { journal.events(walletOf(it), pence).size } shouldBe 10
            }
        }

        test("snapshots keep the outbox small, and a producer started from one numbers on") {
            testActors {
                val wallets = ids.associateWith { spawn(it, wallet(it)) }
                val producer = durableProducer("till", topUpCodec) { id -> wallets.getValue(id) }
                for (n in 0 until 1_500) producer.send(ids[n % 5]) { TopUp(1, it) }

                // 3,000 events written; pruning keeps no more than the 1,000 since the snapshot before the newest.
                journal.read(PersistenceId("surefold-producer", "till")).size shouldBeLessThanOrEqual 1_000
                val _ = durableProducer("till", topUpCodec) { id -> wallets.getValue(id) }.send("w-0") { TopUp(7, it) }

                journal.events(walletOf("w-0"), pence).last() shouldBe 7L
                wallets.getValue("w-0").state.delivered shouldBe mapOf("till" to 301L)
            }
        }

        test("what a start recovers holds room, so a drain waits for it") {
            testActors {
                val crashed = durableProducer("till", topUpCodec) { nowhere }
                for (n in 0 until 3) crashed.send("w-$n") { TopUp(n.toLong(), it) }

                val again = durableProducer("till", topUpCodec, keep = 5, within = Duration.ZERO) { nowhere }

                again.drain(Duration.ZERO) shouldBe false
                again.send("w-0") { TopUp(9, it) } shouldBe Unit.right()
                again.send("w-1") { TopUp(9, it) } shouldBe Unit.right()
                again.send("w-2") { TopUp(9, it) } shouldBe Full.left()
            }
        }

        test("a producer resumed under an actor sends what an earlier run kept, and then drains") {
            testActors {
                val wallets = ids.associateWith { spawn(it, wallet(it)) }
                val crashed = durableProducer("till", topUpCodec, keep = 10) { nowhere }
                for (n in 0 until 10) crashed.send(ids[n % 5]) { TopUp(n.toLong(), it) }
                val resumed = AtomicReference<Producer<TopUp>>()
                val resumer = behaviour<Unit, Unit>(Unit) { _, _, _ -> stay() }.onStart { ctx ->
                    resumed.set(ctx.resumedProducer("till", topUpCodec, 2.seconds) { id -> wallets.getValue(id) }.first)
                }

                spawn("resumer", resumer)

                resumed.get().drain(Duration.ZERO) shouldBe true
                ids.sumOf { journal.events(walletOf(it), pence).size } shouldBe 10
            }
        }

        test("a resumed producer whose journal throws restarts after a backoff, and still drains") {
            val kept = InMemoryJournal()
            testActors(journal = kept) {
                val crashed = durableProducer("till", topUpCodec) { nowhere }
                for (n in 0 until 5) crashed.send(ids[n]) { TopUp(n.toLong(), it) }
            }
            testActors(journal = Flaky(failures = 1, kept)) {
                val wallets = ids.associateWith { spawn(it, wallet(it)) }
                val resumed = AtomicReference<Pair<Producer<TopUp>, ActorRef<*>>>()
                val resumer = behaviour<Unit, Unit>(Unit) { _, _, _ -> stay() }.onStart { ctx ->
                    resumed.set(ctx.resumedProducer("till", topUpCodec, 2.seconds) { id -> wallets.getValue(id) })
                }

                spawn("resumer", resumer)

                val (producer, actor) = resumed.get()
                (actor as TestActor<*, *, *>).delays shouldBe listOf(100.milliseconds)
                producer.drain(Duration.ZERO) shouldBe true
                ids.sumOf { journal.events(walletOf(it), pence).size } shouldBe 5
            }
        }

        test(
            "a producer whose journal throws for a while sends what it writes once it is back, and holds no room",
        ) {
            testActors(journal = Flaky(failures = 4)) {
                val wallets = ids.associateWith { spawn(it, wallet(it)) }
                val producer =
                    durableProducer("till", topUpCodec, keep = 4, within = Duration.ZERO) { wallets.getValue(it) }
                for (n in 0 until 4) producer.send("w-0") { TopUp(n.toLong(), it) }

                producer.send("w-0") { TopUp(7, it) } shouldBe Unit.right()
                journal.events(walletOf("w-0"), pence) shouldContainExactly listOf(7L)
                producer.drain(Duration.ZERO) shouldBe true
            }
        }

        test(
            "on threads, a send while the journal is down answers Unwritten at once, and one after it is back is sent",
        ) {
            val within = 5.seconds
            val database = Down()
            capturingMetrics { captured ->
                scope {
                    journal(database)
                    val wallet = spawn("w-0", wallet("w-0"))
                    val producer = durableProducer("till", topUpCodec, within = within, drainWithin = Duration.ZERO) {
                        wallet
                    }

                    // Past the first, each would wait out a backoff, 100 ms doubling, if it were told to the actor.
                    (1..6L).forEach { pence ->
                        val (sent, took) = measureTimedValue { producer.send("w-0") { TopUp(pence, it) } }
                        sent.leftOrNull().shouldBeInstanceOf<Unwritten> { answer ->
                            answer.cause.shouldBeInstanceOf<SQLException>()
                        }
                        withClue("send $pence") { took shouldBeLessThan within / 10 }
                    }
                    database.back()
                    awaitIdle()

                    producer.send("w-0") { TopUp(7, it) } shouldBe Unit.right()
                    awaitIdle()
                    database.events(walletOf("w-0"), pence) shouldContainExactly listOf(7L)
                    producer.drain(Duration.ZERO) shouldBe true
                }
                captured.counter("surefold.delivery.unwritten") shouldBe 6.0
            }
        }

        test("a producer says once that its journal is down, and once that it is back") {
            val lines = ConcurrentLinkedQueue<LogLine>()
            logger.locally({ line -> lines += line }) {
                testActors(journal = Flaky(failures = 1)) {
                    val producer = durableProducer("till", topUpCodec) { nowhere }
                    producer.send("w-0") { TopUp(1, it) }.leftOrNull().shouldBeInstanceOf<Unwritten>()
                    producer.send("w-0") { TopUp(2, it) } shouldBe Unit.right()
                }
            }
            lines.map { it.level to it.message } shouldContainExactly listOf(
                LogLevel.Warn to "producer till cannot write to its journal, and answers Unwritten until it can: " +
                    "java.sql.SQLException: the database is down",
                LogLevel.Info to "producer till writes to its journal again",
            )
        }

        test("a command the codec refuses, or that cannot be sent again, throws to its sender and holds no room") {
            testActors {
                val wallets = ids.associateWith { spawn(it, wallet(it)) }
                val producer =
                    durableProducer("till", topUpCodec, keep = 1, within = Duration.ZERO) { wallets.getValue(it) }
                val oneOffs = object : EventCodec<OneOff> {
                    override fun encode(event: OneOff) = event.delivery.to.toByteArray()

                    override fun decode(bytes: ByteArray) = OneOff(Delivery("till", String(bytes), 0, Delivery.NoOne))
                }

                shouldThrow<IllegalArgumentException> { producer.send("w-0") { TopUp(-1, it) } }
                shouldThrow<UnsupportedOperationException> {
                    durableProducer("once", oneOffs) { void<OneOff>() }.send("w-0", ::OneOff)
                }
                producer.send("w-0") { TopUp(3, it) } shouldBe Unit.right()

                journal.events(walletOf("w-0"), pence) shouldContainExactly listOf(3L)
                journal.read(PersistenceId("surefold-producer", "once")) shouldBe emptyList()
                producer.drain(Duration.ZERO) shouldBe true
            }
        }

        test("a start that recovers more than it may keep drains only once every one is confirmed") {
            testActors {
                val crashed = durableProducer("till", topUpCodec) { nowhere }
                for (n in 1..5) crashed.send("w-0") { TopUp(n.toLong(), it) }
                val arrived = ArrayDeque<TopUp>()
                val wallet =
                    spawn("wallet", behaviour<TopUp, Unit>(Unit) { _, _, topUp -> stay().also { arrived += topUp } })

                val again = durableProducer("till", topUpCodec, keep = 2) { wallet }
                repeat(2) { arrived.removeFirst().delivery.confirm() }
                again.drain(Duration.ZERO) shouldBe false

                repeat(3) { arrived.removeFirst().delivery.confirm() }
                again.drain(Duration.ZERO) shouldBe true
            }
        }

        // Spec 0146: the journal and snapshot formats are unchanged, so an outbox written before it replays.
        for ((how, snapshots) in listOf("its events" to InMemorySnapshots(), "a snapshot" to oldSnapshot())) {
            test("an outbox written before spec 0146 replays from $how, and numbers on from where it stopped") {
                val journal = InMemoryJournal()
                val _ = journal.append(OLD_OUTBOX, 0, oldEvents)
                testActors(journal = journal, snapshots = snapshots) {
                    val wallets = ids.associateWith { spawn(it, wallet(it)) }
                    val again = durableProducer("till", topUpCodec) { id -> wallets.getValue(id) }
                    again.drain(Duration.ZERO) shouldBe true
                    val _ = again.send("w-0") { TopUp(100, it) }

                    journal.events(walletOf("w-0"), pence) shouldContainExactly listOf(20L, 100L)
                    journal.events(walletOf("w-1"), pence) shouldContainExactly listOf(7L)
                    wallets.getValue("w-0").state.delivered shouldBe mapOf("till" to 3L)
                }
            }
        }
    }
}

/** Tells nobody: an entity whose node has gone. */
private fun <M : Any> void(): ActorRef<M> = object : ActorRef<M> {
    override val address = Address("test", "/void", 0)

    override fun tell(message: M) = Unit
}

private val OLD_OUTBOX = PersistenceId("surefold-producer", "till")

/** A top-up as the till kept it: blank delivery, as `topUpCodec` writes one. */
private fun kept(pence: Long, to: String) = "$pence|till|$to|0".toByteArray()

private fun bytes(write: DataOutputStream.() -> Unit): ByteArray =
    ByteArrayOutputStream().also { DataOutputStream(it).use(write) }.toByteArray()

private fun keptEvent(to: String, sequence: Long, pence: Long) = bytes {
    writeInt(1)
    writeUTF(to)
    writeLong(sequence)
    kept(pence, to).let {
        writeInt(it.size)
        write(it)
    }
}

private fun doneEvent(to: String, sequence: Long) = bytes {
    writeInt(2)
    writeUTF(to)
    writeLong(sequence)
}

/**
 * The till's journal, written byte for byte as a durable producer wrote it before spec 0146. w-0 kept 10 and 20, and
 * confirmed 10; w-1 kept 5 and 7, and confirmed 5. Each still owes its second, and numbers its next 3.
 */
private val oldEvents = listOf(
    keptEvent("w-0", 1, 10),
    keptEvent("w-1", 1, 5),
    doneEvent("w-1", 1),
    keptEvent("w-0", 2, 20),
    keptEvent("w-1", 2, 7),
    doneEvent("w-0", 1),
)

/** A snapshot of the first three of [oldEvents], as one was written before spec 0146. */
private fun oldSnapshot() = InMemorySnapshots().apply {
    save(
        OLD_OUTBOX,
        3,
        bytes {
            writeInt(2)
            writeUTF("w-0")
            writeLong(2)
            writeInt(1)
            writeLong(1)
            kept(10, "w-0").let {
                writeInt(it.size)
                write(it)
            }
            writeUTF("w-1")
            writeLong(2)
            writeInt(0)
        },
    )
}
