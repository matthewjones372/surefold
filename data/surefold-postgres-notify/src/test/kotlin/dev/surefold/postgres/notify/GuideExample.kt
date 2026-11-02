package dev.surefold.postgres.notify

import dev.surefold.stream.Forks
import dev.surefold.stream.Hub
import dev.surefold.stream.HubRefused
import dev.surefold.stream.Running
import dev.surefold.stream.map
import dev.surefold.stream.publishTo
import dev.surefold.stream.runFold
import dev.surefold.stream.start
import java.sql.Connection
import javax.sql.DataSource

sealed interface LiveEvent {
    data class Booked(val bookingId: String) : LiveEvent

    data object Resync : LiveEvent
}

/** One listener for the process, on a connection of its own, fanned out to every open page through [hub]. */
fun liveEvents(
    listenSource: DataSource,
    hub: Hub<LiveEvent>,
    onListening: () -> Unit = {},
    onDisconnected: () -> Unit = {},
): Running<HubRefused, Unit> =
    PostgresNotifications.listen(
        listenSource, channels = setOf("bookings_live"),
        onListening = onListening, onDisconnected = onDisconnected,
    )
        .map { notice ->
            when (notice) {
                is Notice.Received -> LiveEvent.Booked(bookingId = notice.payload)
                Notice.Gap -> LiveEvent.Resync
            }
        }
        .publishTo(hub)
        .runFold(Unit) { _, _ -> }
        .start(Forks())

/** Sent in the booking's own transaction, so no page hears of a booking that rolled back. */
fun booked(connection: Connection, bookingId: String) =
    PostgresNotifications.notify(connection, "bookings_live", bookingId)
