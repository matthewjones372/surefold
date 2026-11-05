package dev.surefold.actor

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

private data class Paid(val pence: Int)

private val paidCodec =
    object : EventCodec<Paid> {
        override fun encode(event: Paid): ByteArray = "${event.pence}".toByteArray()

        override fun decode(bytes: ByteArray): Paid = Paid(String(bytes).toInt())
    }

private val purseCodec =
    object : StateCodec<Int> {
        override fun encode(state: Int): ByteArray = "$state".toByteArray()

        override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
    }

private sealed interface PurseCommand

/** Pays [pence] in, sent reliably. */
private data class PayIn(val pence: Int, override val delivery: Delivery) : PurseCommand, Delivered

/** Puts back the payments kept while the purse was held. */
private data object Unhold : PurseCommand

/** Persists nothing and says so, sent reliably. */
private data class Peek(override val delivery: Delivery) : PurseCommand, Delivered

private val purseId = PersistenceId("purse", "p-1")

/**
 * A purse paid into reliably, snapshotting every [every] entries when asked, and pruning what snapshots cover. While
 * [held], it stashes each payment until [Unhold].
 */
private fun purse(
    peeks: ConcurrentLinkedQueue<Long>,
    every: Int? = null,
    held: AtomicBoolean = AtomicBoolean(),
    batch: Int = 1,
) =
    delivered(
        persistent<PurseCommand, Paid, Int>(
            id = purseId,
            empty = 0,
            codec = paidCodec,
            batch = batch,
            command = { ctx, _, command ->
                when (command) {
                    is PayIn if held.get() -> none().also { ctx.stash(command) }
                    is PayIn -> persist(Paid(command.pence))
                    is Peek -> none().then { peeks += command.delivery.sequence }
                    Unhold -> none().also { ctx.unstashAll() }
                }
            },
            event = { pence, paid -> pence + paid.pence },
            snapshots = every?.let { every(it, purseCodec, prune = Prune.always) },
        ),
    )

/** Spawns the actor that collects each confirmation into [confirmed], which belongs to the test that calls it. */
private fun TestActors.confirms(confirmed: ConcurrentLinkedQueue<Confirmed>) =
    spawn("confirms", behaviour<Confirmed, Unit>(Unit) { _, _, c -> stay().also { confirmed += c } })

object PersistentDeliverySuite : Suite() {
    init {
        test("a command sent twice is applied once and confirmed twice") {
            val confirmed = ConcurrentLinkedQueue<Confirmed>()
            val peeks = ConcurrentLinkedQueue<Long>()
            testActors {
                val confirms = confirms(confirmed)
                val purse = spawn("purse", purse(peeks))
                val first = Delivery("checkout", "p-1", 1, confirms)

                purse.send(PayIn(10, first))
                purse.send(PayIn(10, first))
                purse.send(PayIn(5, first.copy(sequence = 2)))

                purse.state.value shouldBe 15
                purse.state.delivered shouldBe mapOf("checkout" to 2L)
                journal.events(purseId, paidCodec) shouldContainExactly listOf(Paid(10), Paid(5))
                confirmed.map { it.sequence } shouldContainExactly listOf(1L, 1L, 2L)
            }
        }

        test("each producer is counted on its own") {
            val confirmed = ConcurrentLinkedQueue<Confirmed>()
            val peeks = ConcurrentLinkedQueue<Long>()
            testActors {
                val confirms = confirms(confirmed)
                val purse = spawn("purse", purse(peeks))

                purse.send(PayIn(10, Delivery("checkout", "p-1", 1, confirms)))
                purse.send(PayIn(20, Delivery("refunds", "p-1", 1, confirms)))

                purse.state.value shouldBe 30
            }
        }

        test("after a restart, and on the next owner after a move, the duplicate is still dropped") {
            val confirmed = ConcurrentLinkedQueue<Confirmed>()
            val peeks = ConcurrentLinkedQueue<Long>()
            testActors {
                val confirms = confirms(confirmed)
                val purse = spawn("purse", purse(peeks))
                val first = Delivery("checkout", "p-1", 1, confirms)
                purse.send(PayIn(10, first))

                purse.restart()
                purse.send(PayIn(10, first))
                val moved = spawn("moved", purse(peeks))
                moved.send(PayIn(10, first))

                purse.state.value shouldBe 10
                moved.state shouldBe Remembered(10, sequence = 2, delivered = mapOf("checkout" to 1L))
                confirmed.size shouldBe 3
            }
        }

        test("a snapshot remembers the deliveries whose marks were pruned") {
            val confirmed = ConcurrentLinkedQueue<Confirmed>()
            val peeks = ConcurrentLinkedQueue<Long>()
            testActors {
                val confirms = confirms(confirmed)
                val purse = spawn("purse", purse(peeks, every = 2))
                for (sequence in 1L..4L) purse.send(PayIn(10, Delivery("checkout", "p-1", sequence, confirms)))

                val moved = spawn("moved", purse(peeks, every = 2))
                moved.send(PayIn(10, Delivery("checkout", "p-1", 1, confirms)))

                journal.read(purseId).first().sequence shouldBe 7
                moved.state shouldBe Remembered(40, sequence = 8, delivered = mapOf("checkout" to 4L))
            }
        }

        test("a command that persists nothing is dropped as a duplicate until its entity stops") {
            val confirmed = ConcurrentLinkedQueue<Confirmed>()
            val peeks = ConcurrentLinkedQueue<Long>()
            testActors {
                val confirms = confirms(confirmed)
                val purse = spawn("purse", purse(peeks))
                val peek = Peek(Delivery("checkout", "p-1", 1, confirms))

                purse.send(peek)
                purse.send(peek)
                purse.restart()
                purse.send(peek)

                peeks.toList() shouldContainExactly listOf(1L, 1L)
                journal.read(purseId) shouldBe emptyList()
            }
        }

        test("a snapshot of an entity nothing was delivered to is the state's own bytes") {
            val remembered = Remembered(42, sequence = 3)

            val delivered = remembered.copy(delivered = mapOf("checkout" to 7L, "refunds" to 2L))
            encode(purseCodec, remembered).decodeToString() shouldBe "42"
            decode(purseCodec, encode(purseCodec, remembered), 3) shouldBe remembered
            decode(purseCodec, encode(purseCodec, delivered), 3) shouldBe delivered
        }

        // Was a Jupiter @ParameterizedTest over batch 1 and 4: one test for each.
        for (batch in listOf(1, 4)) {
            test("a command stashed is neither confirmed nor remembered until it is handled, batch $batch") {
                val confirmed = ConcurrentLinkedQueue<Confirmed>()
                val peeks = ConcurrentLinkedQueue<Long>()
                testActors {
                    val confirms = confirms(confirmed)
                    val held = AtomicBoolean(true)
                    val purse = spawn("purse", purse(peeks, held = held, batch = batch))

                    purse.send(PayIn(10, Delivery("checkout", "p-1", 1, confirms)))
                    purse.state shouldBe Remembered(0, sequence = 0)
                    confirmed.toList() shouldBe emptyList()

                    held.set(false)
                    purse.send(Unhold)

                    purse.state shouldBe Remembered(10, sequence = 2, delivered = mapOf("checkout" to 1L))
                    confirmed.map { it.sequence } shouldContainExactly listOf(1L)
                }
            }
        }
    }
}
