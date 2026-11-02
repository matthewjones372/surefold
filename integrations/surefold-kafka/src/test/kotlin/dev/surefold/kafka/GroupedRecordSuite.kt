@file:OptIn(KafkaSpi::class)

package dev.surefold.kafka

import dev.surefold.capturingLogs
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactlyInAnyOrder
import dev.surefold.junit.shouldContainKey
import dev.surefold.junit.shouldNotContainKey
import dev.surefold.junit.withClue
import dev.surefold.logInfo
import dev.surefold.stream.Exit
import dev.surefold.stream.Stream
import dev.surefold.stream.TestStreams
import dev.surefold.stream.from
import dev.surefold.stream.run
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.hours

/** Spec 0167: a batch marks each record it holds handled, on a test's clock. */
object GroupedRecordSuite : Suite() {
    /** What one test's records were marked handled at: made per test, since the suite's tests run together. */
    private class Marks {
        val handled = ConcurrentLinkedQueue<Position>()

        fun record(partition: Int, offset: Long, last: Boolean = true): Committed<String> {
            val position = Position("orders", partition, offset)
            val handle = object : Handle {
                override fun handled() {
                    handled += position
                }
            }
            return Committed("$partition-$offset", position, handle.takeIf { last })
        }
    }

    init {
        test("a batch marks each of its records handled once it reaches the end") {
            val marks = Marks()
            val read = listOf(
                marks.record(0, 0),
                marks.record(1, 0),
                marks.record(0, 1),
                marks.record(1, 1),
                marks.record(0, 2),
            )

            val exit = Stream.from(read).groupedRecord(5, 1.hours).runCommitting().run(TestStreams())
                .toCompletableFuture().join()

            exit shouldBe Exit.Done(1L)
            marks.handled.toList() shouldContainExactlyInAnyOrder read.map { it.position }
        }

        test("a record still expanding at a batch's end is committed with the batch its last element is in") {
            val marks = Marks()
            val read = listOf(marks.record(0, 0), marks.record(0, 1, last = false), marks.record(0, 1))

            val exit = Stream.from(read).groupedRecord(2, 1.hours).runCommitting().run(TestStreams())
                .toCompletableFuture().join()

            exit shouldBe Exit.Done(2L)
            withClue("the first batch commits offset 0 only; offset 1 waits for its last element, in the second") {
                marks.handled.toList() shouldBe listOf(Position("orders", 0, 0), Position("orders", 0, 1))
            }
        }

        test("a batch's body logs no record's position, and a record's body still does") {
            val one = Marks().record(0, 1)
            val batch = Committed(listOf(one.value), one.position, Batched(listOf(one)))

            fun annotationsOf(c: Committed<*>) = capturingLogs { logs ->
                c.annotated { logInfo("handled") }
                logs.all().single().annotations
            }

            annotationsOf(batch) shouldNotContainKey "kafka.offset"
            annotationsOf(one) shouldContainKey "kafka.offset"
        }

        test("a batch of batches marks each record handled once, as one batch would") {
            val marks = Marks()
            val read = listOf(marks.record(0, 0), marks.record(1, 0), marks.record(0, 1), marks.record(1, 1))

            val exit = Stream.from(read).groupedRecord(2, 1.hours).groupedRecord(2, 1.hours).runCommitting()
                .run(TestStreams()).toCompletableFuture().join()

            exit shouldBe Exit.Done(1L)
            marks.handled.toList() shouldContainExactlyInAnyOrder read.map { it.position }
        }
    }
}
