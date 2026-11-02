package dev.surefold.bank

import com.sun.net.httpserver.HttpHandler
import dev.surefold.Shared
import dev.surefold.logDebug
import dev.surefold.timeoutOrNull
import java.io.IOException
import java.io.OutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/** One server-sent event: the `event:` line's [type], an `id:` line if it has an [id], and its JSON [data]. */
internal data class Event(val type: String, val data: String, val id: Long? = null) {
    fun frame(): String = buildString {
        append("event: ").append(type).append('\n')
        id?.let { append("id: ").append(it).append('\n') }
        data.lines().forEach { append("data: ").append(it).append('\n') }
        append('\n')
    }
}

/** A comment line: a proxy that closes an idle stream sees traffic, and `EventSource` ignores it. */
internal const val HEARTBEAT = ": heartbeat\n\n"

/** The event type the newest of supersedes the older, so it is the first dropped when a browser falls behind. */
internal const val STATS = "stats"

/** How a stream behaves: a [heartbeat] when idle, a queue of [capacity] events, and closed once full for [fullFor]. */
internal data class Streaming(
    val heartbeat: Duration = 15.seconds,
    val capacity: Int = 256,
    val fullFor: Duration = 10.seconds,
)

/**
 * One browser's stream: a bounded queue that the dashboard offers to without waiting, written out by the virtual
 * thread serving the connection. A full queue drops its oldest `stats` event to make room; with none to drop, the
 * new event is lost, and a queue that stays full for `fullFor` closes the stream.
 */
internal class Stream(private val streaming: Streaming, private val now: () -> Long = System::nanoTime) {
    // Everything the dashboard and the writer share, changed only as a whole, so neither sees the other half done.
    private data class Held(
        val queue: List<Event> = emptyList(),
        val fullSince: Long? = null,
        val open: Boolean = true,
        val out: OutputStream? = null,
    )

    private val held = Shared(Held(), name = "bank.stream")

    val open: Boolean get() = held.peek().open

    /** Queues [event], or makes room for it; false once the stream is closed. */
    fun offer(event: Event): Boolean {
        val at = now()
        val (accepted, closing) = held.modify { was ->
            val stale = was.queue.indexOfFirst { it.type == STATS }
            when {
                !was.open -> was to (false to null)

                was.queue.size < streaming.capacity -> was.copy(queue = was.queue + event, fullSince = null) to
                    (true to null)

                stale >= 0 -> was.copy(queue = was.queue.filterIndexed { i, _ -> i != stale } + event) to (true to null)

                else -> full(was, at)
            }
        }
        closing?.let(::closeLater)
        return accepted
    }

    // A full queue with nothing to drop: the event is lost, and a queue full for `fullFor` closes the stream.
    private fun full(was: Held, at: Long): Pair<Held, Pair<Boolean, OutputStream?>> {
        val since = was.fullSince ?: at
        return if ((at - since).nanoseconds >= streaming.fullFor) {
            was.copy(open = false, fullSince = since) to (false to was.out)
        } else {
            was.copy(fullSince = since) to (true to null)
        }
    }

    /** The events waiting now, oldest first, and none left behind. */
    fun drain(): List<Event> = held.modify { was -> was.copy(queue = emptyList()) to was.queue }

    /** Writes events to [to] as they arrive, or a heartbeat when idle, until closed or a write fails. */
    fun write(to: OutputStream) {
        held.update { it.copy(out = to) }
        try {
            while (open) {
                timeoutOrNull(streaming.heartbeat) { held.awaitUntil { it.queue.isNotEmpty() || !it.open } }
                val next = held.modify { was -> was.copy(queue = was.queue.drop(1)) to was.queue.firstOrNull() }
                if (open) {
                    to.write((next?.frame() ?: HEARTBEAT).encodeToByteArray())
                    to.flush()
                }
            }
        } catch (gone: IOException) {
            logDebug("a stream's browser has gone: ${gone.message}")
            close()
        }
    }

    /**
     * Ends the stream. The connection is closed on a thread of its own, since closing a chunked body writes to a
     * socket that a stalled browser may not be reading, and the dashboard that offered must not wait on it.
     */
    fun close() {
        held.modify { was -> was.copy(open = false) to was.out.takeIf { was.open } }?.let(::closeLater)
    }

    private fun closeLater(stream: OutputStream) {
        Thread.ofVirtual().start { closeQuietly(stream) }
    }
}

/** Every open stream on this node, which the dashboard fans each event out to. */
internal class Hub {
    private val streams = Shared(emptySet<Stream>(), name = "bank.hub")

    val size: Int get() = streams.peek().size

    fun publish(event: Event) = streams.peek().forEach { stream -> if (!stream.offer(event)) leave(stream) }

    fun join(stream: Stream) = streams.update { it + stream }

    fun leave(stream: Stream) {
        streams.update { it - stream }
        stream.close()
    }

    /** Waits up to [within] until the number of streams satisfies [until]; whether it did. */
    fun await(within: Duration, until: (Int) -> Boolean): Boolean =
        timeoutOrNull(within) { streams.awaitUntil { until(it.size) } } != null
}

private fun closeQuietly(stream: OutputStream) {
    try {
        stream.close()
    } catch (gone: IOException) {
        logDebug("a stream's browser had closed it already: ${gone.message}")
    }
}

/** `GET` a stream of [hub]'s events: `text/event-stream`, written on the exchange's own virtual thread. */
internal fun streamOf(hub: Hub, streaming: Streaming) = HttpHandler { exchange ->
    exchange.use {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(200, 0)
        // The headers out now, not with the first event: a client, EventSource included, waits for them before it
        // counts the stream as open, and JDK 25's server holds them until the body is flushed.
        exchange.responseBody.flush()
        val stream = Stream(streaming)
        hub.join(stream)
        try {
            stream.write(exchange.responseBody)
        } finally {
            hub.leave(stream)
        }
    }
}
