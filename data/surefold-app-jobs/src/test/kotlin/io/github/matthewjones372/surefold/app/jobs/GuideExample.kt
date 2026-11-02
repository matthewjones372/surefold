package io.github.matthewjones372.surefold.app.jobs

// The example docs/jobs.md shows, from the imports down: JobsGuideSuite holds the page to this file.
import dev.surefold.clock
import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.liquibase.migrations
import io.github.matthewjones372.surefold.app.singleOf
import io.github.matthewjones372.surefold.jobs.JobCodec
import io.github.matthewjones372.surefold.jobs.JobHandlers
import io.github.matthewjones372.surefold.jobs.JobKind
import io.github.matthewjones372.surefold.jobs.JobResult
import io.github.matthewjones372.surefold.jobs.JobSettings
import io.github.matthewjones372.surefold.jobs.Jobs
import java.sql.Connection
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class Reminder(val orderId: String)

object ReminderCodec : JobCodec<Reminder> {
    override val version = 1

    override fun encode(payload: Reminder) = """{"orderId": "${payload.orderId}"}"""

    override fun decode(version: Int, json: String) =
        Reminder(json.substringAfter("\"orderId\": \"").substringBefore('"'))
}

object SendReminder : JobKind<Reminder>("send-reminder", ReminderCodec, deadline = 30.seconds, concurrency = 4)

fun interface Mailer {
    fun remind(orderId: String): Boolean
}

/** Called inside the transaction that places the order, on its connection: both commit, or neither does. */
fun remindLater(connection: Connection, orderId: String) {
    Jobs.enqueue(
        connection,
        SendReminder,
        Reminder(orderId),
        dedupeKey = "reminder-$orderId",
        runAt = clock.get().now().plusSeconds(3_600),
    )
}

fun reminders(mailer: Mailer): Module =
    migrations("db/changelog.yaml") +
        singleOf { -> JobSettings() } +
        jobWorker(
            JobHandlers.of(
                SendReminder.handledBy { job ->
                    if (mailer.remind(job.payload.orderId)) JobResult.Done else JobResult.Retry(5.minutes, "busy")
                },
            ),
        )
