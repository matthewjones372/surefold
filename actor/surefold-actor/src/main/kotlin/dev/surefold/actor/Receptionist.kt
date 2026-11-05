package dev.surefold.actor

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Who is listed under each key, and who follows each key, for one scope or one test. A change is told to its followers
 * while the lock is held, so each follower hears the listings in the order they changed; telling one must therefore
 * never wait.
 */
internal class Receptionist {
    private class Following(val follower: Any, val tell: (Set<ActorRef<*>>) -> Unit)

    private val lock = ReentrantLock()
    private val listed = HashMap<ServiceKey<*>, LinkedHashSet<ActorRef<*>>>()
    private val following = HashMap<ServiceKey<*>, MutableList<Following>>()

    fun register(key: ServiceKey<*>, ref: ActorRef<*>) = lock.withLock {
        if (listed.getOrPut(key, ::LinkedHashSet).add(ref)) changed(key)
    }

    /** Tells [tell] the listing under [key] now, and whenever it changes, until [follower] is forgotten. */
    fun subscribe(key: ServiceKey<*>, follower: Any, tell: (Set<ActorRef<*>>) -> Unit) = lock.withLock {
        val follows = Following(follower, tell)
        following.getOrPut(key, ::mutableListOf) += follows
        follows.tell(listing(key))
    }

    /** Takes [ref] off every key it is listed under and stops everything it follows: it stopped or restarted. */
    fun forget(ref: ActorRef<*>) = lock.withLock {
        following.values.forEach { follows -> follows.removeAll { it.follower === ref } }
        listed.filterValues { it.remove(ref) }.keys.forEach(::changed)
    }

    @Suppress("UNCHECKED_CAST")
    fun <M : Any> find(key: ServiceKey<M>): Set<ActorRef<M>> = lock.withLock { listing(key) as Set<ActorRef<M>> }

    private fun listing(key: ServiceKey<*>): Set<ActorRef<*>> = listed[key]?.toSet().orEmpty()

    private fun changed(key: ServiceKey<*>) {
        val now = listing(key)
        following[key]?.forEach { it.tell(now) }
    }
}
