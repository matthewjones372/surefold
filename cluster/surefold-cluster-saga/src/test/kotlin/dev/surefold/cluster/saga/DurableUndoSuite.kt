package dev.surefold.cluster.saga

import dev.surefold.Schedule
import dev.surefold.Settled
import dev.surefold.Shared
import dev.surefold.Undo
import dev.surefold.actor.InMemoryJournal
import dev.surefold.actor.Journal
import dev.surefold.actor.JournalConflict
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.StateCodec
import dev.surefold.actor.StoredEvent
import dev.surefold.capturingMetrics
import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import dev.surefold.policy

private object Word : StateCodec<String> {
    override fun encode(state: String): ByteArray = state.toByteArray()

    override fun decode(bytes: ByteArray): String = String(bytes)
}

/** Holds seats, releases them once per key, and can be told to fail releases for a while. */
private class Seats {
    val held = Shared(emptySet<String>())
    val releases = Shared(emptyList<String>())
    val failing = Shared(0)

    fun hold(key: String): String {
        held.update { it + key }
        return key
    }

    fun find(key: String): Settled<String> =
        if (key in held.peek() || "$key/undo" in releases.peek()) Settled.Happened(key) else Settled.DidNot

    fun release(key: String, seat: String) {
        if (failing.modify { (it - 1).coerceAtLeast(0) to it } > 0) error("releases are down")
        releases.update { if (key in it) it else it + key }
        held.update { it - seat }
    }
}

private fun booking(seats: Seats, undo: dev.surefold.Policy? = null) =
    durableSaga("booking", Word, Word, Word, undo = undo) { trip ->
        step("flight", Word, { key -> seats.hold(key) }, { key -> seats.find(key) }) { key, seat, _ ->
            seats.release(key, seat)
        }
        step("hotel", Word, { key -> seats.hold(key) }, { key -> seats.find(key) }) { key, seat, _ ->
            seats.release(key, seat)
        }
        if (trip == "sold-out") fail("no rooms")
        "booked"
    }

/** Throws instead of the append numbered [dieAt], as a node dying would. */
private class DyingAt(private val inner: Journal, private val dieAt: Int) : Journal by inner {
    private var appends = 0

    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>): Either<JournalConflict, Long> {
        check(++appends != dieAt) { "the node died before append $dieAt" }
        return inner.append(id, expected, events)
    }

    override fun read(id: PersistenceId, from: Long): List<StoredEvent> = inner.read(id, from)
}

object DurableUndoSuite : Suite() {
    init {
        test("a declared fail undoes the steps that ended, newest first, each told why, then ends on the left") {
            val seats = Seats()
            val told = Shared(emptyList<Undo<String>>())
            val saga = durableSaga("booking", Word, Word, Word) { _ ->
                step("flight", Word, { key -> seats.hold(key) }, undo = { key, seat, why ->
                    told.update { it + why }
                    seats.release(key, seat)
                })
                step("hotel", Word, { key -> seats.hold(key) }, undo = { key, seat, _ -> seats.release(key, seat) })
                fail("no rooms")
            }

            saga.run(InMemoryJournal(), "b", "trip") shouldBe "no rooms".left()

            seats.releases.peek() shouldBe listOf("b/hotel/undo", "b/flight/undo")
            told.peek() shouldBe listOf(Undo.Failed("no rooms"))
            seats.held.peek() shouldBe emptySet()
        }

        test("a node that dies at any append while undoing leaves a saga that, resumed, undoes each step once") {
            for (dieAt in 1..9) {
                val seats = Seats()
                val journal = InMemoryJournal()
                runCatchingDeath { booking(seats).run(DyingAt(journal, dieAt), "b", "sold-out") }

                booking(seats).run(journal, "b", "sold-out") shouldBe "no rooms".left()

                seats.held.peek() shouldBe emptySet()
                seats.releases.peek().toSet() shouldBe setOf("b/hotel/undo", "b/flight/undo")
            }
        }

        test("an undo that still fails leaves the saga Stuck, and resume undoes what is left once fixed") {
            val seats = Seats()
            val journal = InMemoryJournal()
            seats.failing.update { 1 }

            capturingMetrics { measured ->
                shouldThrow<Stuck>({ booking(seats).run(journal, "b", "sold-out") }) { stuck ->
                    stuck.step shouldBe "hotel"
                }
                measured.counter("surefold.saga.stuck") shouldBe 1.0
            }
            seats.held.peek() shouldBe setOf("b/flight", "b/hotel")

            booking(seats).resume(journal, "b") shouldBe "no rooms".left()

            seats.held.peek() shouldBe emptySet()
        }

        test("the saga's undo policy retries a failing undo before calling it Stuck") {
            val seats = Seats()
            seats.failing.update { 2 }

            booking(seats, undo = policy("booking.undo") { retry(Schedule.recurs(3)) })
                .run(InMemoryJournal(), "b", "sold-out") shouldBe "no rooms".left()

            seats.held.peek() shouldBe emptySet()
        }

        test("a saga that ends well runs no undo") {
            val seats = Seats()

            booking(seats).run(InMemoryJournal(), "b", "fine")

            seats.releases.peek() shouldBe emptyList()
        }
    }
}

private fun runCatchingDeath(block: () -> Unit) {
    try {
        block()
    } catch (died: IllegalStateException) {
        check(died.message.orEmpty().startsWith("the node died")) { "expected a death, got $died" }
    }
}
