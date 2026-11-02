package dev.surefold.idempotency

import dev.surefold.ByteChunk
import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import io.github.matthewjones372.surefold.actor.journal.jdbc.Postgres
import io.github.matthewjones372.surefold.actor.journal.jdbc.migrate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import javax.sql.DataSource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Keys reserved atomically, answers replayed, and leases taken over (spec 0226). */
object IdempotencySuite : Suite() {
    private val noon = Instant.parse("2026-10-26T12:00:00Z")
    private val key = IdempotencyKey("acme", "pay-42")
    private val created =
        StoredResponse(201, listOf("location" to "/payments/42", "x-trace" to "t"), ByteChunk.from("{\"id\":42}"))

    init {
        test("fifty threads reserving one key at once get exactly one reservation, and the rest see it in progress") {
            val store = PostgresIdempotency(database())
            val answers = ConcurrentLinkedQueue<Reservation>()
            val ready = CountDownLatch(50)
            (1..50).map {
                Thread.ofVirtual().start {
                    ready.countDown()
                    ready.await()
                    answers += store.reserve(key, "f1")
                }
            }.forEach { it.join() }
            answers.count { it is Reservation.Reserved } shouldBe 1
            answers.count { it == Reservation.InProgress } shouldBe 49
        }

        test("a completed key replays its stored response to the same request") {
            val store = PostgresIdempotency(database())
            val token = (store.reserve(key, "f1") as Reservation.Reserved).token
            store.complete(key, token, created) shouldBe true
            store.reserve(key, "f1") shouldBe Reservation.Replay(created)
        }

        test("a key used for a different request is a mismatch, while it runs and after") {
            val store = PostgresIdempotency(database())
            val token = (store.reserve(key, "f1") as Reservation.Reserved).token
            store.reserve(key, "f2") shouldBe Reservation.Mismatch
            store.complete(key, token, created)
            store.reserve(key, "f2") shouldBe Reservation.Mismatch
        }

        test("a key whose holder died is taken over once its lease passes, and the dead holder writes nothing") {
            val store = PostgresIdempotency(database(), lease = 30.seconds)
            val time = TestClock(noon)
            clock.locally(time) {
                val dead = (store.reserve(key, "f1") as Reservation.Reserved).token
                time.adjust(29.seconds)
                store.reserve(key, "f1") shouldBe Reservation.InProgress
                time.adjust(2.seconds)
                val retry = (store.reserve(key, "f1") as Reservation.Reserved).token
                store.complete(key, dead, created) shouldBe false
                store.release(key, dead) shouldBe false
                store.complete(key, retry, created) shouldBe true
                store.reserve(key, "f1") shouldBe Reservation.Replay(created)
            }
        }

        test("a released key can be reserved again at once, for the retry of a request whose handler failed") {
            val store = PostgresIdempotency(database())
            val token = (store.reserve(key, "f1") as Reservation.Reserved).token
            store.release(key, token) shouldBe true
            (store.reserve(key, "f1") is Reservation.Reserved) shouldBe true
        }

        test("an expired key is a new one, even for a different request") {
            val store = PostgresIdempotency(database())
            val time = TestClock(noon)
            clock.locally(time) {
                val token = (store.reserve(key, "f1", ttl = 1.hours) as Reservation.Reserved).token
                store.complete(key, token, created)
                time.adjust(2.hours)
                (store.reserve(key, "f2") is Reservation.Reserved) shouldBe true
            }
        }

        test("the same key in two scopes is two keys") {
            val store = PostgresIdempotency(database())
            (store.reserve(IdempotencyKey("acme", "k"), "f1") is Reservation.Reserved) shouldBe true
            (store.reserve(IdempotencyKey("globex", "k"), "f2") is Reservation.Reserved) shouldBe true
        }

        test("a key needs a scope and a key, and is not too long") {
            shouldThrow<IllegalArgumentException> { IdempotencyKey("", "k") }
            shouldThrow<IllegalArgumentException> { IdempotencyKey("s", "k".repeat(256)) }
            PostgresIdempotency(database()).complete(key, UUID.randomUUID(), created) shouldBe false
            created.toString() shouldBe "StoredResponse(201, [(location, /payments/42), (x-trace, t)], 9 bytes)"
            val copied = ByteChunk.from(created.body.toByteArray())
            (created == StoredResponse(201, created.headers, copied)) shouldBe true
            created.hashCode() shouldBe StoredResponse(201, created.headers, copied).hashCode()
        }

        test("a body made from an array keeps what the array held then, however the array changes after") {
            val array = "{\"id\":42}".toByteArray()
            val stored = StoredResponse(201, emptyList(), ByteChunk.from(array))
            array.fill(0)
            stored.body.decodeToString() shouldBe "{\"id\":42}"
        }
    }

    fun database(): DataSource = Postgres.empty().also { source ->
        source.migrate("surefold/idempotency/postgres.sql")
    }
}
