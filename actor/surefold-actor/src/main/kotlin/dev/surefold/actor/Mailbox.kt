package dev.surefold.actor

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater

/** One message in a [Mailbox]. */
internal class MailboxNode(@Volatile @JvmField var message: Any?) {
    @Volatile
    @JvmField
    var next: MailboxNode? = null
}

/**
 * Many senders, one reader: a send is a single swap that never retries, where a lock-free queue's CAS on its tail
 * retries whenever two senders meet. Only the actor's own activation reads. The same queue Pekko's mailbox uses.
 *
 * An actor's cell extends it, so its head and tail are fields of the cell rather than objects of their own: a tell to
 * an actor that has gone cold misses in the cache once for the cell, not once for each.
 */
internal open class Mailbox {

    @Volatile
    @JvmField
    var tail: MailboxNode = MailboxNode(null)

    // Read and written only by the activation that holds the actor.
    @Volatile
    @JvmField
    var head: MailboxNode = tail

    fun add(message: Any) {
        val node = MailboxNode(message)
        // Between the swap and the link the queue reads as empty, so a sender schedules the actor only after linking.
        TAIL.getAndSet(this, node).next = node
    }

    fun poll(): Any? {
        val next = head.next ?: return null
        head = next
        return next.message.also { next.message = null }
    }

    fun isNotEmpty(): Boolean = head.next != null

    /** The next message, left where it is: only the reader calls this, as only it calls [poll]. */
    fun peek(): Any? = head.next?.message
}

private val TAIL: AtomicReferenceFieldUpdater<Mailbox, MailboxNode> =
    AtomicReferenceFieldUpdater.newUpdater(Mailbox::class.java, MailboxNode::class.java, "tail")
