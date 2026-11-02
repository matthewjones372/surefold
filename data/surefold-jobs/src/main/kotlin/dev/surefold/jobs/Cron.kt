package dev.surefold.jobs

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A five-field cron expression, minute, hour, day of month, month and day of week, read in [zone]. A field is `*`, a
 * number, a range `a-b`, any of those with a step `/n`, or a comma-separated list of them. Day of week is 0 to 7, both
 * ends Sunday. When both day fields are restricted, a day matching either fires, as cron has always done.
 *
 * Across a change of the clocks: a local time the clocks went forward over fires at the first instant after the gap,
 * and a local time that happens twice, as they go back, fires once, at the earlier.
 */
class Cron private constructor(
    val expression: String,
    val zone: ZoneId,
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val days: Set<Int>,
    private val months: Set<Int>,
    private val weekdays: Set<Int>,
    private val anyDay: Boolean,
    private val anyWeekday: Boolean,
) {

    /** The first firing strictly after [after], or null if there is none within eight years. */
    fun next(after: Instant): Instant? {
        val start = after.atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)
        var date = start.toLocalDate()
        val last = date.plusYears(HORIZON_YEARS)
        while (!date.isAfter(last)) {
            if (date.monthValue in months && dayMatches(date)) {
                firstOn(date, after)?.let { return it }
            }
            date = date.plusDays(1)
        }
        return null
    }

    private fun dayMatches(date: LocalDate): Boolean {
        val day = date.dayOfMonth in days
        val weekday = date.dayOfWeek.value % DAYS_IN_WEEK in weekdays
        return when {
            anyDay && anyWeekday -> true
            anyDay -> weekday
            anyWeekday -> day
            else -> day || weekday
        }
    }

    private fun firstOn(date: LocalDate, after: Instant): Instant? {
        for (hour in hours.sorted()) {
            for (minute in minutes.sorted()) {
                val fires = resolve(LocalDateTime.of(date, LocalTime.of(hour, minute)))
                if (fires.isAfter(after)) return fires
            }
        }
        return null
    }

    /** The instant [local] names in [zone]: the earlier of two, or the end of the gap it fell in. */
    private fun resolve(local: LocalDateTime): Instant {
        val offsets = zone.rules.getValidOffsets(local)
        if (offsets.isNotEmpty()) return local.atOffset(offsets.first()).toInstant()
        return zone.rules.getTransition(local).instant
    }

    override fun toString(): String = "$expression ($zone)"

    /** How a recurring job's rule is kept in its row: the five fields, then the zone. */
    internal fun stored(): String = "$expression ${zone.id}"

    companion object {
        private const val HORIZON_YEARS = 8L
        private const val DAYS_IN_WEEK = 7
        private const val FIELDS = 5
        private val MINUTES = 0..59
        private val HOURS = 0..23
        private val DAYS = 1..31
        private val MONTHS = 1..12
        private val WEEKDAYS = 0..7

        /** Reads what [stored] wrote. */
        internal fun read(stored: String): Cron =
            parse(stored.substringBeforeLast(' '), ZoneId.of(stored.substringAfterLast(' ')))

        /** Reads [expression], and throws [IllegalArgumentException] naming the field it could not read. */
        fun parse(expression: String, zone: ZoneId): Cron {
            val fields = expression.trim().split(Regex("\\s+"))
            require(fields.size == FIELDS) {
                "A cron expression has five fields, minute hour day month weekday, not \"$expression\"."
            }
            val (minute, hour, day, month, weekday) = fields
            return Cron(
                expression = fields.joinToString(" "),
                zone = zone,
                minutes = field(minute, MINUTES, "minute"),
                hours = field(hour, HOURS, "hour"),
                days = field(day, DAYS, "day of month"),
                months = field(month, MONTHS, "month"),
                weekdays = field(weekday, WEEKDAYS, "weekday").map { it % DAYS_IN_WEEK }.toSet(),
                anyDay = day == "*",
                anyWeekday = weekday == "*",
            )
        }

        private fun field(text: String, range: IntRange, name: String): Set<Int> =
            text.split(',').flatMap { part -> part(part, range, name) }.toSet()

        private fun part(part: String, range: IntRange, name: String): List<Int> {
            val (span, step) = part.split('/').let { pieces ->
                require(pieces.size <= 2) { "The $name field cannot read \"$part\"." }
                pieces[0] to (pieces.getOrNull(1)?.let { number(it, 1..range.last, name) } ?: 1)
            }
            val from: Int
            val to: Int
            when {
                span == "*" -> {
                    from = range.first
                    to = range.last
                }

                '-' in span -> {
                    from = number(span.substringBefore('-'), range, name)
                    to = number(span.substringAfter('-'), range, name)
                    require(from <= to) { "The $name field's range \"$span\" runs backwards." }
                }

                else -> {
                    from = number(span, range, name)
                    to = if (step > 1) range.last else from
                }
            }
            return (from..to step step).toList()
        }

        private fun number(text: String, range: IntRange, name: String): Int {
            val value = requireNotNull(text.toIntOrNull()) { "The $name field cannot read \"$text\"." }
            require(value in range) { "The $name field takes ${range.first} to ${range.last}, not $value." }
            return value
        }
    }
}
