package dev.surefold

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A bounded hand-off between forks of one scope (spec 0038): a producer [send]s, a consumer [receive]s or iterates,
 * and at most [capacity] values wait between them, so a producer ahead of its consumer parks rather than running away.
 * A capacity of zero hands each value straight from a sender to a receiver.
 *
 * The producer [close]s it at the end of its data, and a consumer drains what was sent before that. The consumer
 * [cancel]s it to stop early: a send parked or still to come answers `false`, so a producer loop ends without an
 * interrupt. The scope that opened it cancels it when it closes.
 */
class Channel<A : Any> internal constructor(val capacity: Int) : Iterable<A> {

    init {
        require(capacity >= 0) { "a channel holds no fewer than zero values, and this one was asked for $capacity" }
    }

    private val lock = ReentrantLock()
    private val buffer = ArrayDeque<A>()
    private val senders = ArrayDeque<Parked>()
    private val receivers = ArrayDeque<Parked>()
    private var closed = false
    private var cancelled = false

    /**
     * [value] handed on, parking while the channel is full: true once it is in the channel or a receiver's hands,
     * false if the consumer cancelled. Sending on a closed channel throws [IllegalStateException].
     */
    fun send(value: A): Boolean {
        val parked = lock.withLock {
            when (offer(value, null, 0)) {
                Taken -> return true
                Stopped -> return false
            }
            Parked(value, Selection(), 0).also(senders::addLast)
        }
        return sent(await(parked, senders))
    }

    /** The next value, parking while the channel is empty: null once it is closed and drained, or cancelled. */
    fun receive(): A? {
        val parked = lock.withLock {
            when (val now = poll(null, 0)) {
                Empty -> Parked(null, Selection(), 0).also(receivers::addLast)
                else -> return received(now)
            }
        }
        return received(await(parked, receivers))
    }

    /** No more values: a later [send] throws, and a receiver gets what was already sent, then null. */
    fun close() = lock.withLock {
        closed = true
        // A receiver parks only on an empty channel with no sender waiting: nothing more will reach it.
        generateSequence { receivers.match(null, 0) as Parked? }.forEach { it.selection.deliver(Stopped) }
    }

    /** The consumer has stopped: what waits is dropped, and every parked or later send answers false. */
    fun cancel() = lock.withLock {
        cancelled = true
        buffer.clear()
        generateSequence { senders.match(null, 0) as Parked? }.forEach { it.selection.deliver(Stopped) }
        generateSequence { receivers.match(null, 0) as Parked? }.forEach { it.selection.deliver(Stopped) }
    }

    /**
     * [value] put in a receiver's hands or the buffer now, claiming [ours] for [clause] first if a select offers it:
     * [Taken], [Stopped] if the consumer cancelled, [Full], or [Lost] if [ours] was already won. Called under the lock.
     */
    private fun offer(value: A, ours: Selection?, clause: Int): Any {
        if (cancelled) return if (ours.claims(clause)) Stopped else Lost
        check(!closed) { "a value was sent on a closed channel" }
        val receiver = receivers.match(ours, clause)
        return when {
            receiver === Lost -> Lost
            receiver is Parked -> Taken.also { receiver.selection.deliver(value) }
            buffer.size >= capacity -> Full
            !ours.claims(clause) -> Lost
            else -> Taken.also { buffer.addLast(value) }
        }
    }

    /**
     * A value taken now, claiming [ours] for [clause] first if a select asks: the value, [Stopped] once the channel is
     * closed and drained or cancelled, [Empty], or [Lost] if [ours] was already won. Called under the lock.
     */
    private fun poll(ours: Selection?, clause: Int): Any {
        if (buffer.isNotEmpty()) return if (ours.claims(clause)) takeBuffered() else Lost
        val sender = senders.match(ours, clause)
        return when {
            sender === Lost -> Lost

            sender is Parked -> checkNotNull(sender.value) { "a parked sender brings a value" }
                .also { sender.selection.deliver(Taken) }

            !closed && !cancelled -> Empty

            ours.claims(clause) -> Stopped

            else -> Lost
        }
    }

    /** The first value buffered; the room it leaves goes to the first sender waiting for it. */
    private fun takeBuffered(): A {
        (senders.match(null, 0) as? Parked)?.let { sender ->
            @Suppress("UNCHECKED_CAST")
            buffer.addLast(checkNotNull(sender.value as A?) { "a parked sender brings a value" })
            sender.selection.deliver(Taken)
        }
        return buffer.removeFirst()
    }

    /** Whether a plain call, or a select's [clause] as it claims, may go ahead. */
    private fun Selection?.claims(clause: Int): Boolean = this == null || claim(clause)

    /**
     * The first thread parked here that is not [ours]'s own, claimed together with [ours] for [clause] and taken off
     * the queue: null if there is none, or [Lost] if [ours] was already won. One whose select was won elsewhere is
     * dropped on the way, as its select would drop it.
     */
    private fun ArrayDeque<Parked>.match(ours: Selection?, clause: Int): Any? {
        val each = iterator()
        while (each.hasNext()) {
            val parked = each.next()
            if (parked.selection === ours) continue
            val claim = ours?.claimWith(clause, parked.selection, parked.clause)
                ?: if (parked.selection.claim(parked.clause)) Claim.Both else Claim.TheirsGone
            when (claim) {
                Claim.OursGone -> return Lost
                Claim.TheirsGone -> each.remove()
                Claim.Both -> return parked.also { each.remove() }
            }
        }
        return null
    }

    /** A select's receive: the answer now, or [Pending] once parked here, or [Lost]. */
    internal fun enterReceive(selection: Selection, clause: Int): Any = lock.withLock {
        when (val now = poll(selection, clause)) {
            Empty -> Pending.also { receivers.addLast(Parked(null, selection, clause)) }
            else -> now
        }
    }

    /** A select's send: the answer now, or [Pending] once parked here, or [Lost]. */
    internal fun enterSend(value: A, selection: Selection, clause: Int): Any = lock.withLock {
        when (val now = offer(value, selection, clause)) {
            Full -> Pending.also { senders.addLast(Parked(value, selection, clause)) }
            else -> now
        }
    }

    /** Whatever [selection] left parked here, gone. */
    internal fun leaveSelection(selection: Selection) = lock.withLock {
        receivers.removeAll { it.selection === selection }
        senders.removeAll { it.selection === selection }
    }

    /** What a receive was answered with, as the value or null. */
    @Suppress("UNCHECKED_CAST")
    internal fun received(answer: Any): A? = if (answer === Stopped) null else answer as A

    /** What a send was answered with, as whether its value was taken. */
    internal fun sent(answer: Any): Boolean = when (answer) {
        Taken -> true
        Stopped -> false
        else -> error("a sender is answered taken or stopped, not $answer")
    }

    /** How many threads are parked on the channel, for a test that says a select left none behind. */
    internal fun parked(): Int = lock.withLock { receivers.size + senders.size }

    /** How many values wait in the channel now, for a test that says it never holds more than [capacity]. */
    internal fun buffered(): Int = lock.withLock { buffer.size }

    /** Every value received until the channel is closed and drained, or cancelled. */
    override fun iterator(): Iterator<A> = object : Iterator<A> {
        private var next: A? = null
        private var ended = false

        override fun hasNext(): Boolean {
            if (next == null && !ended) next = receive().also { if (it == null) ended = true }
            return next != null
        }

        override fun next(): A {
            hasNext()
            val value = next ?: throw NoSuchElementException("the channel is closed and drained")
            next = null
            return value
        }
    }

    /**
     * Parks until [parked] is answered. Interrupted first, it leaves [queue] and throws, so a sender's value is never
     * taken by a receiver after the sender gave up on it; answered first, the interrupt is kept for the caller.
     */
    private fun await(parked: Parked, queue: ArrayDeque<Parked>): Any {
        val answer = parked.selection.await {}
        if (answer === Interrupted) {
            lock.withLock { queue.remove(parked) }
            throw InterruptedException("interrupted while parked on a channel")
        }
        return answer
    }
}

/** A [selection]'s thread parked on a channel for its [clause], with the value it brings as a sender. */
private class Parked(val value: Any?, val selection: Selection, val clause: Int)

/** A channel of [capacity] between this scope's forks, cancelled when the scope closes (spec 0038). */
fun <A : Any> Scope<*>.channel(capacity: Int): Channel<A> = Channel<A>(capacity).also { channel ->
    onClose { channel.cancel() }
}

private object Taken

private object Stopped

private object Empty

private object Full
