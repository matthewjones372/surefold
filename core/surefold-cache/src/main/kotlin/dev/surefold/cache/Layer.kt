package dev.surefold.cache

import java.time.Instant

/** A value as a layer keeps it: when it was looked up, and when it stops being an answer. */
data class Stored<V>(val value: V, val storedAt: Instant, val expiresAt: Instant)

/**
 * Memory under a cache's own, nearest first (spec 0246): a store every instance shares, such as Redis, so instances
 * that miss in their own memory ask it before the source. A layer holds only good values, never a failure, so one
 * instance's outage is not shared with the rest. A layer that throws is a miss, counted and logged, never the
 * caller's error.
 *
 * An operation must not invoke or synchronously await get, refresh or invalidate on its owning cache.
 * Direct calls and inherited Surefold work are rejected while that operation is active; stats and other caches
 * remain callable. Arbitrary foreign-executor cycles cannot be detected and are unsupported.
 */
interface Layer<K : Any, V> {
    fun read(key: K): Stored<V>?

    fun write(key: K, stored: Stored<V>)

    fun remove(key: K)

    fun clear()
}
