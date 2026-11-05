package dev.surefold.actor.journal.jdbc

import dev.surefold.actor.EventCodec
import dev.surefold.actor.Journal
import dev.surefold.actor.JournalConflict
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.awaitIdle
import dev.surefold.actor.events
import dev.surefold.actor.journal
import dev.surefold.actor.onStart
import dev.surefold.actor.persistent
import dev.surefold.actor.spawn
import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBeLessThanOrEqual
import dev.surefold.junit.shouldContainExactly
import dev.surefold.scope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

private val cents =
    object : EventCodec<Int> {
        override fun encode(event: Int): ByteArray = "$event".toByteArray()

        override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
    }

private class Counted(private val kept: Journal) : Journal by kept {
    val appends = AtomicInteger()

    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>): Either<JournalConflict, Long> =
        kept.append(id, expected, events).also { appends.incrementAndGet() }
}

/** Spec 0086 on Postgres: commands waiting at once become one statement's worth of rows each batch. */
object BatchedAppendSuite : Suite() {
    init {
        test("a thousand commands waiting at once reach Postgres in a handful of appends, every event in order") {
            val id = PersistenceId("merchant", "m-1")
            val journal = Counted(JdbcJournal(Postgres.fresh()))
            val opened = CountDownLatch(1)
            val merchant =
                persistent<Int, Int, Int>(
                        id = id,
                        empty = 0,
                        codec = cents,
                        command = { _, _, paid -> persist(paid) },
                        event = { sum, paid -> sum + paid },
                        batch = 64,
                    )
                    .onStart { opened.await() }

            scope {
                journal(journal)
                val ref = spawn("merchant", merchant)
                (1..1_000).forEach(ref::tell)
                opened.countDown()
                awaitIdle()
            }

            journal.appends.get() shouldBeLessThanOrEqual 20
            journal.events(id, cents) shouldContainExactly (1..1_000).toList()
        }
    }
}
