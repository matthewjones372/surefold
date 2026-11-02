package dev.surefold.cache.redis

import dev.surefold.cache.Cache
import dev.surefold.cache.Invalidation
import io.lettuce.core.RedisChannelHandler
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisConnectionStateListener
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.pubsub.RedisPubSubAdapter
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/** A cache told over Redis pub/sub that a value changed (spec 0246), for services with Redis and no Postgres. */
object RedisInvalidations {

    /** Tells every instance listening on [channel] that [key] changed. */
    fun publish(commands: RedisCommands<String, String>, channel: String, key: String) {
        commands.publish(channel, key)
    }

    /**
     * Invalidates [cache]'s key, as [key] reads it, for each message on [channel], on a pub/sub connection of its own
     * from [client]. A connection lost and made again drops every entry, since a message may have been missed. Close
     * what this answers to stop listening.
     */
    fun <K : Any> listen(
        client: RedisClient,
        channel: String,
        cache: Cache<K, *, *>,
        key: (String) -> K,
    ): AutoCloseable {
        val pubSub = client.connectPubSub()
        val connected = AtomicBoolean(false)
        val reconnects = object : RedisConnectionStateListener {
            override fun onRedisConnected(connection: RedisChannelHandler<*, *>, address: SocketAddress) {
                if (connection === pubSub && connected.getAndSet(true)) cache.invalidate(Invalidation.All)
            }
        }
        client.addListener(reconnects)
        pubSub.addListener(
            object : RedisPubSubAdapter<String, String>() {
                override fun message(on: String, message: String) {
                    if (on == channel) cache.invalidate(Invalidation.Key(key(message)))
                }
            },
        )
        pubSub.sync().subscribe(channel)
        connected.set(true)
        return AutoCloseable {
            client.removeListener(reconnects)
            pubSub.close()
        }
    }
}
