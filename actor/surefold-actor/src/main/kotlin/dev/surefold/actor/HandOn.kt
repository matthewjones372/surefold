package dev.surefold.actor

import dev.surefold.Carriers

/** A seam for surefold's own modules that hand messages on from a turn to receivers that may be busy (spec 0095). */
@RequiresOptIn("A seam for surefold's own modules that hand messages on from a step to receivers that may be busy.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class PlumbingSeam

/**
 * Messages a turn hands on to receivers that may be busy (spec 0095). Each is told if its receiver has room, or has
 * stopped, where a tell is a dead letter. Otherwise it is kept, in order, under its key, and everything after it for
 * that key waits behind it. Past [KEEP_AT_MOST] kept under one key, a message is a dead letter for being
 * [DeadLetter.Why.Full]. The turn calls [drain] on a timer of its own while anything is kept, and `awaitIdle` counts
 * what is kept as not yet handled. Each is kept with what its handling carried, and handed on inside it (spec 0122).
 */
@PlumbingSeam
class HandOn<K : Any, M : Any> {
    private class Kept<M : Any>(val to: ActorRef<M>, val message: M, val carried: Map<String, String>)

    private val waiting = LinkedHashMap<K, ArrayDeque<Kept<M>>>()

    /** The keys anything is kept under. */
    val keys: Set<K> get() = waiting.keys

    operator fun contains(key: K): Boolean = key in waiting

    /** Hands [message] to [to] under [key]: whether it was kept, so that a drain is due. */
    fun tell(ctx: Ctx<*>, key: K, to: ActorRef<M>, message: M): Boolean {
        val kept = waiting[key]
        return when {
            kept == null && to.tellIfRoom(message) -> false

            kept != null && kept.size >= KEEP_AT_MOST -> {
                ctx.deadLetter(DeadLetter(to.address, message, DeadLetter.Why.Full))
                false
            }

            else -> {
                (kept ?: ArrayDeque<Kept<M>>().also { waiting[key] = it }) += Kept(to, message, Carriers.capture())
                ctx.kept(1)
                true
            }
        }
    }

    /** Hands on what is kept, in order, as far as each receiver has room: whether anything is still kept. */
    fun drain(ctx: Ctx<*>): Boolean {
        var handed = 0
        waiting.values.removeIf { kept ->
            while (kept.isNotEmpty() &&
                kept.first().let { Carriers.within(it.carried) { it.to.tellIfRoom(it.message) } }
            ) {
                kept.removeFirst()
                handed++
            }
            kept.isEmpty()
        }
        ctx.kept(-handed)
        return waiting.isNotEmpty()
    }

    /** Takes what is kept under [key], in order, each to [each] inside what it carried: its receiver has gone. */
    fun take(ctx: Ctx<*>, key: K, each: (M) -> Unit) {
        val kept = waiting.remove(key) ?: return
        ctx.kept(-kept.size)
        kept.forEach { Carriers.within(it.carried) { each(it.message) } }
    }

    /** Drops what is kept under [key]: its receiver is owed nothing more. */
    fun forget(ctx: Ctx<*>, key: K) = take(ctx, key) {}

    /** Every kept message, a dead letter for [why]: the turn's own actor is stopping. */
    fun drop(ctx: Ctx<*>, why: DeadLetter.Why) {
        waiting.values.forEach { kept ->
            kept.forEach { ctx.deadLetter(DeadLetter(it.to.address, it.message, why)) }
            ctx.kept(-kept.size)
        }
        waiting.clear()
    }
}
