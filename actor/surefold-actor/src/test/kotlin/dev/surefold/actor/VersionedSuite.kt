package dev.surefold.actor

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldThrow

/** Version 1: an order placed, with its total in pounds and pence together. */
private data class PlacedV1(val totalPence: Long)

/** Version 2: the total split into its lines. */
private data class PlacedV2(val lines: List<Long>)

/** Version 3, today's: each line an event of its own. */
private data class LineAdded(val pence: Long)

private val v1 =
    object : EventCodec<PlacedV1> {
        override fun encode(event: PlacedV1) = "${event.totalPence}".toByteArray()

        override fun decode(bytes: ByteArray) = PlacedV1(String(bytes).toLong())
    }

private val v2 =
    object : EventCodec<PlacedV2> {
        override fun encode(event: PlacedV2) = event.lines.joinToString(",").toByteArray()

        override fun decode(bytes: ByteArray) = PlacedV2(String(bytes).split(",").map(String::toLong))
    }

private val v3 =
    object : EventCodec<LineAdded> {
        override fun encode(event: LineAdded) = "${event.pence}".toByteArray()

        override fun decode(bytes: ByteArray) = LineAdded(String(bytes).toLong())
    }

/** 1 to 2: a total is one line. 2 to 3: each line is an event of its own. */
private val upgrades =
    mapOf(
        1 to Upgrade { bytes -> listOf(v2.encode(PlacedV2(listOf(v1.decode(bytes).totalPence)))) },
        2 to Upgrade { bytes -> v2.decode(bytes).lines.map { v3.encode(LineAdded(it)) } },
    )

private val orders = versioned(current = 3, codec = v3, upgrades = upgrades)

private val orderId = PersistenceId("order", "o-1")

private fun order() =
    persistent<Long, LineAdded, List<Long>>(
        id = orderId,
        empty = emptyList(),
        codec = orders,
        command = { _, _, pence -> persist(LineAdded(pence)) },
        event = { lines, added -> lines + added.pence },
    )

object VersionedSuite : Suite() {
    init {
        test("events written as versions 1, with no prefix, 2 and 3 replay as version 3, one of them as two") {
            testActors {
                // As a service wrote them before it versioned its codec, then after, then today.
                journal.append(orderId, 0, listOf(v1.encode(PlacedV1(500))))
                journal.append(orderId, 1, listOf(versionedBytes(2, v2.encode(PlacedV2(listOf(100, 250))))))
                val order = spawn("order", order())
                order.send(75)

                order.state shouldBe Remembered(listOf(500L, 100, 250, 75), sequence = 3)
                journal.events(orderId, orders) shouldContainExactly listOf(500L, 100, 250, 75).map(::LineAdded)
            }
        }

        test("an event reads back as it was written, and one read that upgrades to several asks for all of them") {
            val upgraded = versionedBytes(2, v2.encode(PlacedV2(listOf(1, 2))))
            orders.decode(orders.encode(LineAdded(42))) shouldBe LineAdded(42)
            orders.decodeAll(upgraded) shouldBe listOf(LineAdded(1), LineAdded(2))
            shouldThrow<IllegalStateException> { orders.decode(upgraded) }
        }

        test("upgrades that skip a version, or reach past the current one, are refused when the codec is built") {
            shouldThrow<IllegalArgumentException>({ versioned(3, v3, mapOf(2 to upgrades.getValue(2))) }) { refusal ->
                refusal.message shouldContain "[1]"
            }
            shouldThrow<IllegalArgumentException>({ versioned(2, v3, upgrades) }) { refusal ->
                refusal.message shouldContain "[2]"
            }
            shouldThrow<IllegalArgumentException> { versioned(0, v3) }
        }

        test(
            "an event newer than the codec fails its entity's replay, naming the entity, the event and the version",
        ) {
            testActors {
                journal.append(orderId, 0, listOf(versionedBytes(4, v3.encode(LineAdded(1)))))
                val expected =
                    "PersistenceId(kind=order, id=o-1) cannot be recovered: " +
                        "event 1 is version 4, newer than this codec's 3"
                shouldThrow<IllegalStateException>({ spawn("order", order()) }) { failure ->
                    failure.message shouldBe expected
                }
            }
        }
    }
}
