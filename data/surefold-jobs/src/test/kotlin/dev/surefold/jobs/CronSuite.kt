package dev.surefold.jobs

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldThrow
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset.UTC

/** A five-field cron in a time zone, across the clocks changing (spec 0218). */
object CronSuite : Suite() {
    private val london = ZoneId.of("Europe/London")

    /** The next [n] firings of [cron] after [from]. */
    private fun firings(cron: Cron, from: String, n: Int): List<String> =
        generateSequence(cron.next(Instant.parse(from))) { cron.next(it) }.take(n).map { it.toString() }.toList()

    init {
        test("a local time the clocks go forward over fires at the first instant after the gap") {
            // Europe/London goes from 01:00 GMT to 02:00 BST on 2026-03-29, so there is no 01:30 that day.
            firings(Cron.parse("30 1 * * *", london), "2026-03-27T12:00:00Z", 3) shouldContainExactly listOf(
                "2026-03-28T01:30:00Z",
                "2026-03-29T01:00:00Z",
                "2026-03-30T00:30:00Z",
            )
        }

        test("a local time that happens twice as the clocks go back fires once, at the earlier") {
            // Europe/London goes from 02:00 BST back to 01:00 GMT on 2026-10-25, so 01:30 happens twice that day.
            firings(Cron.parse("30 1 * * *", london), "2026-10-24T12:00:00Z", 3) shouldContainExactly listOf(
                "2026-10-25T00:30:00Z",
                "2026-10-26T01:30:00Z",
                "2026-10-27T01:30:00Z",
            )
        }

        test("asked from inside the repeated hour, the next firing is the next day's") {
            Cron.parse("30 1 * * *", london).next(Instant.parse("2026-10-25T01:10:00Z")).toString() shouldBe
                "2026-10-26T01:30:00Z"
        }

        test("a daily 08:00 in Europe/London is 07:00 UTC in summer and 08:00 UTC in winter") {
            firings(Cron.parse("0 8 * * *", london), "2026-10-24T00:00:00Z", 2) shouldContainExactly listOf(
                "2026-10-24T07:00:00Z",
                "2026-10-25T08:00:00Z",
            )
        }

        test("steps, ranges and lists") {
            firings(Cron.parse("*/20 9-10 * * *", UTC), "2026-10-26T00:00:00Z", 7) shouldContainExactly listOf(
                "2026-10-26T09:00:00Z",
                "2026-10-26T09:20:00Z",
                "2026-10-26T09:40:00Z",
                "2026-10-26T10:00:00Z",
                "2026-10-26T10:20:00Z",
                "2026-10-26T10:40:00Z",
                "2026-10-27T09:00:00Z",
            )
            firings(Cron.parse("0 12 1,15 * *", UTC), "2026-10-26T00:00:00Z", 2) shouldContainExactly listOf(
                "2026-11-01T12:00:00Z",
                "2026-11-15T12:00:00Z",
            )
        }

        test("a weekday field, with Sunday as 0 or 7") {
            // 2026-10-26 is a Monday.
            firings(Cron.parse("0 9 * * 1-5", UTC), "2026-10-30T10:00:00Z", 2) shouldContainExactly listOf(
                "2026-11-02T09:00:00Z",
                "2026-11-03T09:00:00Z",
            )
            Cron.parse("0 9 * * 7", UTC).next(Instant.parse("2026-10-26T00:00:00Z")).toString() shouldBe
                "2026-11-01T09:00:00Z"
        }

        test("with both day fields restricted, a day matching either fires") {
            // The 1st of the month, or any Friday.
            firings(Cron.parse("0 0 1 * 5", UTC), "2026-10-26T00:00:00Z", 3) shouldContainExactly listOf(
                "2026-10-30T00:00:00Z",
                "2026-11-01T00:00:00Z",
                "2026-11-06T00:00:00Z",
            )
        }

        test("a firing is strictly after the instant asked from") {
            Cron.parse("0 9 * * *", UTC).next(Instant.parse("2026-10-26T09:00:00Z")).toString() shouldBe
                "2026-10-27T09:00:00Z"
        }

        test("a date that never comes has no next firing") {
            Cron.parse("0 0 30 2 *", UTC).next(Instant.parse("2026-10-26T00:00:00Z")) shouldBe null
        }

        test("an expression it cannot read is refused, naming the field") {
            shouldThrow<IllegalArgumentException> { Cron.parse("0 9 * *", UTC) }
            shouldThrow<IllegalArgumentException>({ Cron.parse("61 9 * * *", UTC) }) { failure ->
                failure.message shouldBe "The minute field takes 0 to 59, not 61."
            }
            shouldThrow<IllegalArgumentException> { Cron.parse("0 9 5-1 * *", UTC) }
            shouldThrow<IllegalArgumentException> { Cron.parse("0 9 * JAN *", UTC) }
        }
    }
}
