package dev.surefold.postgres.notify

/** What a listener hears from Postgres. */
sealed interface Notice {

    /** A notification on [channel], with the [payload] it was sent with, from the backend whose process id is [pid]. */
    data class Received(val channel: String, val payload: String, val pid: Int) : Notice

    /** The connection was lost and is listening again: what was sent meanwhile is gone, so refetch what is shown. */
    data object Gap : Notice
}
