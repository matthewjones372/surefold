package dev.surefold.actor

/**
 * An actor's stash: messages it has kept, and those it has put back to be handled before its mailbox. Only the actor's
 * own activation touches it.
 */
internal class Stash(private val capacity: Int, private val owner: String) {
    private val kept = ArrayDeque<Any>()
    private val replay = ArrayDeque<Any>()

    /** Keeps [message]; a full stash fails the turn, so that supervision decides rather than a message being lost. */
    fun keep(message: Any) {
        check(kept.size < capacity) { "the stash of $owner is full, at $capacity" }
        kept.addLast(message)
    }

    /** Puts every kept message back, in the order kept, ahead of any still being put back. */
    fun unstashAll() {
        replay.addAll(0, kept)
        kept.clear()
    }

    fun isReplaying(): Boolean = replay.isNotEmpty()

    /** Every message still here, those put back first, leaving it empty. */
    fun drain(): List<Any> =
        (replay + kept).also {
            replay.clear()
            kept.clear()
        }

    /** The next message put back, or null when there is none. */
    fun next(): Any? = replay.removeFirstOrNull()
}
