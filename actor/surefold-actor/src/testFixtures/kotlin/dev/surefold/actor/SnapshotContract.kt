package dev.surefold.actor

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeNull
import dev.surefold.assert.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * What every [SnapshotStore] does, as tests a store's own test class inherits: it answers [store], a store with
 * nothing in it, and runs these unchanged. A service with a store of its own runs them the same way.
 */
abstract class SnapshotContract {

    /** A store with no snapshots in it, fresh for each test. */
    abstract fun store(): SnapshotStore

    private val sam = PersistenceId("diary", "sam")

    private fun SnapshotStore.held(id: PersistenceId) = latest(id)?.let { it.sequence to String(it.bytes) }

    @Test
    fun `an id with no snapshot has none, and one saved is the latest`() {
        val store = store()

        store.latest(sam).shouldBeNull()
        store.save(sam, 100, "at 100".toByteArray())

        store.held(sam) shouldBe (100L to "at 100")
    }

    @Test
    fun `a newer snapshot replaces the one held, and an older one is ignored`() {
        val store = store()
        store.save(sam, 100, "at 100".toByteArray())

        store.save(sam, 200, "at 200".toByteArray())
        store.save(sam, 150, "at 150".toByteArray())

        store.held(sam) shouldBe (200L to "at 200")
    }

    @Test
    fun `each id has its own snapshot, whether the kind or the id differs`() {
        val store = store()
        val ids = listOf(sam, PersistenceId("diary", "kim"), PersistenceId("letters", "sam"))

        ids.forEachIndexed { i, id -> store.save(id, i + 1L, "${id.kind}/${id.id}".toByteArray()) }

        ids.forEachIndexed { i, id -> store.held(id) shouldBe (i + 1L to "${id.kind}/${id.id}") }
    }

    @Test
    fun `the store keeps its own copy of the bytes`() {
        val store = store()
        val saved = "one".toByteArray()
        store.save(sam, 1, saved)

        saved[0] = 'x'.code.toByte()
        store.latest(sam).shouldNotBeNull().bytes[0] = 'y'.code.toByte()

        store.held(sam) shouldBe (1L to "one")
    }

    @Test
    fun `of many saves racing in any order, the newest is the one kept`() {
        val store = store()
        val sequences = (1L..64L).shuffled()
        val start = CountDownLatch(1)

        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("snapshot-contract-saver-", 0).factory())
            .use { savers ->
            val saves = sequences.map { sequence ->
                savers.submit {
                    start.await()
                    store.save(sam, sequence, "at $sequence".toByteArray())
                }
            }
            start.countDown()
            saves.forEach { it.get() }
        }

        store.held(sam) shouldBe (64L to "at 64")
    }
}
