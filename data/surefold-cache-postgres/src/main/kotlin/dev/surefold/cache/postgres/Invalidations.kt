package dev.surefold.cache.postgres

import dev.surefold.cache.Cache
import dev.surefold.cache.Invalidation
import dev.surefold.postgres.notify.Notice
import io.github.matthewjones372.surefold.stream.Run
import io.github.matthewjones372.surefold.stream.Stream
import io.github.matthewjones372.surefold.stream.map
import io.github.matthewjones372.surefold.stream.runFold

/**
 * Each notice as what a cache is told (spec 0246): a payload is the key [key] reads from it, and a [Notice.Gap] is
 * [Invalidation.All], since a change may have been missed while the listener was away.
 */
fun <K : Any> Stream<Nothing, Notice>.invalidations(key: (String) -> K): Stream<Nothing, Invalidation<K>> = map {
    when (it) {
        is Notice.Received -> Invalidation.Key(key(it.payload))
        Notice.Gap -> Invalidation.All
    }
}

/**
 * Tells [cache] of every notice, as [invalidations] reads it, for as long as the run lasts: start it on the graph's
 * `StreamBackend` beside the cache, and send with `PostgresNotifications.notify` in the transaction that changes the
 * row. The run answers how many invalidations it applied.
 */
fun <K : Any> Stream<Nothing, Notice>.invalidating(cache: Cache<K, *, *>, key: (String) -> K): Run<Nothing, Long> =
    invalidations(key).runFold(0L) { applied, invalidation ->
        cache.invalidate(invalidation)
        applied + 1
    }
