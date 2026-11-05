package dev.surefold.actor

/**
 * Which of a durable producer's commands hold room (spec 0146). Each one kept takes room before it is kept, but a
 * recovered one holds none until room is claimed for it, and there may be more recovered than there is room for. Room
 * is never released while a recovered command waits for some: it is handed on, so a drain cannot take it in between and
 * answer while that command is unconfirmed. Pure: the producer's actor moves the semaphore by what this says, and only
 * room is kept here, never the journal's state.
 */
internal data class Holding(val held: Set<Pair<String, Long>> = emptySet()) {
    /** Also holding [to]'s [sequence], which took its room before it was kept. */
    fun kept(to: String, sequence: Long): Holding = Holding(held + (to to sequence))

    /** How many of [outboxes]' commands hold no room yet. */
    fun unheld(outboxes: Outboxes<*>): Int = waiting(outboxes).count()

    /** Holding [free] more of [outboxes]' commands that hold no room yet, the first first, and how many it took. */
    fun claim(outboxes: Outboxes<*>, free: Int): Pair<Holding, Int> {
        val taken = waiting(outboxes).take(free).toList()
        return Holding(held + taken) to taken.size
    }

    /**
     * After [to]'s [sequence] was confirmed, leaving [outboxes]: its room handed to a command that holds none, or
     * released, and true when it was released. A command that held no room releases none.
     */
    fun confirmed(outboxes: Outboxes<*>, to: String, sequence: Long): Pair<Holding, Boolean> {
        if (to to sequence !in held) return this to false
        val rest = Holding(held - (to to sequence))
        val waiting = rest.waiting(outboxes).firstOrNull() ?: return rest to true
        return Holding(rest.held + waiting) to false
    }

    private fun waiting(outboxes: Outboxes<*>) =
        outboxes.queues
            .asSequence()
            .flatMap { (to, kept) -> kept.asSequence().map { to to it.sequence } }
            .filter { it !in held }
}
