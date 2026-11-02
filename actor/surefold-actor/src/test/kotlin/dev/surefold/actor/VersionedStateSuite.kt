package dev.surefold.actor

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow

/** A till's takings: version 1 kept the pence alone; version 2 keeps the currency too. */
private data class Takings(val pence: Long, val currency: String)

private val takingsV1 = object : StateCodec<Long> {
    override fun encode(state: Long) = "$state".toByteArray()

    override fun decode(bytes: ByteArray) = String(bytes).toLong()
}

private val takingsV2 = object : StateCodec<Takings> {
    override fun encode(state: Takings) = "${state.pence},${state.currency}".toByteArray()

    override fun decode(bytes: ByteArray) = String(bytes).split(",").let { (pence, currency) ->
        Takings(pence.toLong(), currency)
    }
}

/** Every till took pounds until the currency was kept. */
private val takings = versionedState(
    current = 2,
    codec = takingsV2,
    upgrades = mapOf(1 to StateUpgrade { bytes -> takingsV2.encode(Takings(takingsV1.decode(bytes), "GBP")) }),
)

private val rung = object : EventCodec<Long> {
    override fun encode(event: Long) = "$event".toByteArray()

    override fun decode(bytes: ByteArray) = String(bytes).toLong()
}

private fun till(id: PersistenceId) = persistent<Long, Long, Takings>(
    id = id,
    empty = Takings(0, "GBP"),
    codec = rung,
    command = { _, _, pence -> persist(pence) },
    event = { takings, pence -> takings.copy(pence = takings.pence + pence) },
    snapshots = every(1_000, takings),
)

object VersionedStateSuite : Suite() {
    init {
        test(
            "an entity recovered from a snapshot of an older version and newer events reaches what a full replay does",
        ) {
            testActors {
                val snapshotted = PersistenceId("till", "t-1")
                val replayed = PersistenceId("till", "t-2")
                listOf(snapshotted, replayed).forEach { id ->
                    journal.append(id, 0, listOf(100L, 250L).map(rung::encode))
                }
                // Saved before the currency was kept: version 1, with no mark.
                snapshots.save(snapshotted, 2, encode(takingsV1, Remembered(350L, 2)))
                listOf(snapshotted, replayed).forEach { id -> journal.append(id, 2, listOf(rung.encode(75))) }
                // Only the snapshot holds what came before it now, so a recovery that ignored it could not start.
                (journal as JournalPruning).deleteTo(snapshotted, 2)

                val fromSnapshot = spawn("from-snapshot", till(snapshotted))
                val fromEvents = spawn("from-events", till(replayed))

                fromSnapshot.state shouldBe Remembered(Takings(425, "GBP"), sequence = 3)
                fromSnapshot.state shouldBe fromEvents.state
            }
        }

        test("a state reads back as it was written, and a chain with a gap is refused when the codec is built") {
            takings.decode(takings.encode(Takings(1, "EUR"))) shouldBe Takings(1, "EUR")
            shouldThrow<IllegalArgumentException> { versionedState(3, takingsV2, mapOf(2 to StateUpgrade { it })) }
        }
    }
}
