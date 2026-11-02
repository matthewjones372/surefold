package dev.surefold.actor

/** A command an outbox keeps, numbered. */
internal data class KeptCommand<C>(val sequence: Long, val command: C)

/** A command for the producer's actor to send [to]: the head of its outbox. */
internal data class Send<C>(val to: String, val sequence: Long, val command: C)

/**
 * Each entity's kept commands, in the order they were kept (spec 0079). The head is in flight; the next is sent once
 * the head is confirmed, so an entity has one command in flight at a time. Pure: a producer's actor performs the
 * [Send]s it is given, and keeps room and meters itself (spec 0146).
 */
internal data class Outboxes<C>(val queues: Map<String, List<KeptCommand<C>>> = emptyMap()) {
    /** These outboxes keeping [command] for [to], and its send when [to] had nothing in flight. */
    fun kept(to: String, sequence: Long, command: C): Pair<Outboxes<C>, Send<C>?> {
        val queue = queues[to].orEmpty()
        val send = if (queue.isEmpty()) Send(to, sequence, command) else null
        return Outboxes(queues + (to to queue + KeptCommand(sequence, command))) to send
    }

    /** These outboxes past [to]'s head and the send of its next, or null when [sequence] is not [to]'s head. */
    fun confirmed(to: String, sequence: Long): Pair<Outboxes<C>, Send<C>?>? {
        val queue = queues[to]
        if (queue?.firstOrNull()?.sequence != sequence) return null
        val rest = queue.drop(1)
        val next = rest.firstOrNull()?.let { Send(to, it.sequence, it.command) }
        return Outboxes(if (rest.isEmpty()) queues - to else queues + (to to rest)) to next
    }

    /** Every entity's head: what a resend sends again. */
    fun heads(): List<Send<C>> =
        queues.mapNotNull { (to, queue) -> queue.firstOrNull()?.let { Send(to, it.sequence, it.command) } }
}
