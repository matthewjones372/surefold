package dev.surefold.actor

import dev.surefold.assert.shouldThrow
import dev.surefold.fail.Either
import dev.surefold.fail.right
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import org.junit.jupiter.api.Test

class InMemoryJournalTest : JournalContract() {
    override fun journal(): Journal = InMemoryJournal()
}

/**
 * A journal that reads the last sequence number and writes after it as two steps, and lets two writers both read before
 * either writes: what a journal without an atomic append does under load, every time rather than sometimes.
 */
private class Racy : Journal {
    private val kept = ConcurrentHashMap<PersistenceId, List<StoredEvent>>()
    private val bothRead = CyclicBarrier(2)

    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>): Either<JournalConflict, Long> {
        val before = kept[id].orEmpty()
        bothRead.await()
        kept[id] = before + events.mapIndexed { i, bytes -> StoredEvent(expected + i + 1, bytes) }
        return (expected + events.size).right()
    }

    override fun read(id: PersistenceId, from: Long): List<StoredEvent> = kept[id].orEmpty().drop((from - 1).toInt())
}

class JournalContractTest {
    @Test
    fun `a journal that lets two racing writers both succeed fails the contract`() {
        val racy =
            object : JournalContract() {
                override fun journal(): Journal = Racy()
            }

        shouldThrow<AssertionError> {
            racy.`of two writers racing to append after the same event, exactly one succeeds`()
        }
    }
}

class InMemoryFeedTest : FeedContract<InMemoryJournal>() {
    override fun journal() = InMemoryJournal()
}

class InMemoryOffsetsTest : OffsetContract() {
    override fun store(): OffsetStore = InMemoryOffsets()
}

class InMemoryPruneTest : PruneContract<InMemoryJournal>() {
    override fun journal() = InMemoryJournal()
}
