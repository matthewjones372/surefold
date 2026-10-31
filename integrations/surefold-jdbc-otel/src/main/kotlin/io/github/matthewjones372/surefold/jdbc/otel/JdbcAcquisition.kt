package io.github.matthewjones372.surefold.jdbc.otel

import io.github.matthewjones372.surefold.surefoldLocal
import io.opentelemetry.instrumentation.jdbc.datasource.JdbcTelemetry
import java.sql.Connection
import java.sql.ConnectionBuilder
import java.sql.ShardingKey
import java.sql.ShardingKeyBuilder
import java.util.Properties
import java.util.concurrent.CancellationException
import javax.sql.DataSource

internal class JdbcDataSource(
    private val source: DataSource,
    telemetry: JdbcTelemetry,
) : DataSource by source {
    private val current = surefoldLocal<Acquisition?> { null }
    private val instrumented = telemetry.wrap(object : DataSource by source {
        override fun getConnection(): Connection {
            val acquisition = checkNotNull(current.get())
            return guardMetadata(acquisition.borrow(), acquisition)
        }
    })

    override fun getConnection(): Connection = acquire { source.connection }
    override fun getConnection(username: String?, password: String?): Connection = acquire {
        source.getConnection(username, password)
    }
    override fun createConnectionBuilder(): ConnectionBuilder =
        TracedConnectionBuilder(source.createConnectionBuilder(), ::acquire)
    override fun createShardingKeyBuilder(): ShardingKeyBuilder = source.createShardingKeyBuilder()

    @Suppress("TooGenericExceptionCaught") // Release the borrowed connection without replacing the original failure.
    private fun acquire(borrow: () -> Connection): Connection {
        val acquisition = Acquisition(borrow)
        return current.locally(acquisition) {
            val connection = try {
                instrumented.connection
            } catch (failure: Throwable) {
                acquisition.active = false
                val original = acquisition.failure ?: failure
                if (original !== failure) original.addSuppressed(failure)
                acquisition.releaseAndThrow(original)
            }
            acquisition.active = false
            acquisition.failure?.let { acquisition.releaseAndThrow(it) }
            connection
        }
    }
}

private class TracedConnectionBuilder(
    private var delegate: ConnectionBuilder,
    private val acquire: (() -> Connection) -> Connection,
) : ConnectionBuilder {
    override fun user(username: String?): ConnectionBuilder = apply { delegate = delegate.user(username) }
    override fun password(password: String?): ConnectionBuilder = apply { delegate = delegate.password(password) }
    override fun shardingKey(key: ShardingKey?): ConnectionBuilder = apply { delegate = delegate.shardingKey(key) }
    override fun superShardingKey(key: ShardingKey?): ConnectionBuilder = apply {
        delegate = delegate.superShardingKey(key)
    }
    override fun build(): Connection = acquire { delegate.build() }
}

private class Acquisition(private var pending: (() -> Connection)?) {
    var active = true
    var connection: Connection? = null
    var failure: Throwable? = null

    fun borrow(): Connection {
        val borrow = checkNotNull(pending)
        pending = null
        return borrow().also { connection = it }
    }

    fun rejected(cause: Throwable) {
        if (failure == null) failure = cause
    }

    @Suppress("TooGenericExceptionCaught") // Cleanup failures are suppressed onto the failure being preserved.
    fun releaseAndThrow(cause: Throwable): Nothing {
        try {
            connection?.close()
        } catch (cleanup: Throwable) {
            if (cleanup !== cause) cause.addSuppressed(cleanup)
        }
        throw cause
    }
}

/** Upstream catches Throwable from this metadata read; rethrow it before exposing the borrowed connection. */
private fun guardMetadata(
    connection: Connection,
    acquisition: Acquisition,
): Connection = object : Connection by connection {
    override fun beginRequest(): Unit = connection.beginRequest()
    override fun endRequest(): Unit = connection.endRequest()
    override fun setShardingKeyIfValid(key: ShardingKey?, superKey: ShardingKey?, timeout: Int): Boolean =
        connection.setShardingKeyIfValid(key, superKey, timeout)
    override fun setShardingKeyIfValid(key: ShardingKey?, timeout: Int): Boolean =
        connection.setShardingKeyIfValid(key, timeout)
    override fun setShardingKey(key: ShardingKey?, superKey: ShardingKey?): Unit =
        connection.setShardingKey(key, superKey)
    override fun setShardingKey(key: ShardingKey?): Unit = connection.setShardingKey(key)

    override fun getClientInfo(): Properties {
        if (!acquisition.active) return connection.clientInfo
        return criticalMetadata(acquisition, Properties()) {
            val properties = connection.clientInfo
            object : Properties() {
                override fun getProperty(key: String?): String? =
                    criticalMetadata(acquisition, null) { properties.getProperty(key) }
            }
        }
    }
}

@Suppress("TooGenericExceptionCaught") // Upstream catches every Error; retain it for rethrow before handoff.
private inline fun <A> criticalMetadata(acquisition: Acquisition, fallback: A, read: () -> A): A = try {
    read()
} catch (cancelled: CancellationException) {
    acquisition.rejected(cancelled)
    fallback
} catch (interrupted: InterruptedException) {
    acquisition.rejected(interrupted)
    fallback
} catch (fatal: Error) {
    acquisition.rejected(fatal)
    fallback
}
