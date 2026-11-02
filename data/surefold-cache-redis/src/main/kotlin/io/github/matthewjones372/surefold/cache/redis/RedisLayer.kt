package io.github.matthewjones372.surefold.cache.redis

import dev.surefold.cache.Layer
import dev.surefold.cache.Stored
import dev.surefold.clock
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import java.nio.ByteBuffer
import java.time.Instant

/** How a value is turned into bytes for a store every instance shares, and back: the service's to choose. */
interface Codec<V> {
    fun encode(value: V): ByteArray

    fun decode(bytes: ByteArray): V
}

/**
 * A [Layer] on Redis (spec 0246): each value under `prefix:key`, as its time stored, its expiry and [codec]'s bytes,
 * with Redis's own expiry set to the same instant, so Redis drops it when the cache would. [connection] is the
 * service's, with `String` keys and `ByteArray` values.
 */
class RedisLayer<K : Any, V>(
    private val connection: StatefulRedisConnection<String, ByteArray>,
    private val prefix: String,
    private val codec: Codec<V>,
    private val keyOf: (K) -> String = { it.toString() },
) : Layer<K, V> {
    init {
        require(prefix.isNotBlank() && ':' !in prefix) { "a prefix is one word, with no ':', not '$prefix'" }
    }

    override fun read(key: K): Stored<V>? = connection.sync().get(named(key))?.let { bytes ->
        val held = ByteBuffer.wrap(bytes)
        val storedAt = Instant.ofEpochMilli(held.long)
        val expiresAt = Instant.ofEpochMilli(held.long)
        Stored(codec.decode(bytes.copyOfRange(HEADER, bytes.size)), storedAt, expiresAt)
    }

    override fun write(key: K, stored: Stored<V>) {
        val left = stored.expiresAt.toEpochMilli() - clock.get().now().toEpochMilli()
        if (left <= 0) return
        val value = codec.encode(stored.value)
        val bytes = ByteBuffer.allocate(HEADER + value.size)
            .putLong(stored.storedAt.toEpochMilli())
            .putLong(stored.expiresAt.toEpochMilli())
            .put(value)
            .array()
        connection.sync().set(named(key), bytes, SetArgs().px(left))
    }

    override fun remove(key: K) {
        connection.sync().del(named(key))
    }

    /** Deletes every key under this layer's prefix, a page at a time, and no other. */
    @Suppress("SpreadOperator") // one DEL per page of at most PAGE keys: the copy is of that page, not of the store
    override fun clear() {
        val commands = connection.sync()
        val matching = ScanArgs().match("$prefix:*").limit(PAGE)
        var cursor = commands.scan(matching)
        while (true) {
            if (cursor.keys.isNotEmpty()) commands.del(*cursor.keys.toTypedArray())
            if (cursor.isFinished) return
            cursor = commands.scan(ScanCursor.of(cursor.cursor), matching)
        }
    }

    private fun named(key: K): String = "$prefix:${keyOf(key)}"

    private companion object {
        const val HEADER = Long.SIZE_BYTES * 2
        const val PAGE = 500L
    }
}
