package io.github.matthewjones372.surefold.postgres.notify

import dev.surefold.Clock
import dev.surefold.Schedule
import dev.surefold.ScheduleStep
import dev.surefold.clock
import dev.surefold.counter
import dev.surefold.increment
import dev.surefold.logInfo
import dev.surefold.logWarn
import dev.surefold.resourceScope
import io.github.matthewjones372.surefold.stream.Stream
import io.github.matthewjones372.surefold.stream.blocking
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Postgres's `LISTEN` as a [Stream], and `pg_notify` to send to it. */
object PostgresNotifications {

    /**
     * Every notification sent on [channels] after this run's connection is listening, in commit order. Each run takes
     * one connection from [source], held until the run stops, so [source] should be one of its own, outside the pool;
     * run it once per process and share it through a `Hub`.
     *
     * A connection that cannot be made, or is lost, is made again after the delays [reconnect] answers, waited on the
     * clock bound where this is called; [Notice.Gap] comes first once a lost one is listening again. A [reconnect]
     * that stops ends the run with the last failure.
     */
    fun listen(
        source: DataSource,
        channels: Set<String>,
        reconnect: Schedule<Throwable, Any?> = RECONNECT,
    ): Stream<Nothing, Notice> = listen(source, channels, reconnect, {}, {})

    /** Lifecycle callbacks observe the owned subscription without adding a notice to its data. */
    fun listen(
        source: DataSource,
        channels: Set<String>,
        reconnect: Schedule<Throwable, Any?> = RECONNECT,
        onListening: () -> Unit,
        onDisconnected: () -> Unit,
    ): Stream<Nothing, Notice> {
        require(channels.isNotEmpty()) { "PostgresNotifications.listen names no channel to listen on" }
        channels.forEach(::checked)
        val time = clock.get()
        return Stream.blocking(
            open = { Listening(source, channels, reconnect, time, onListening, onDisconnected) },
            next = Listening::next,
            wake = Listening::wake,
            close = Listening::close,
        )
    }

    /** Sends [payload] on [channel] in [connection]'s transaction, so listeners hear it once that commits. */
    fun notify(connection: Connection, channel: String, payload: String) {
        checked(channel)
        val bytes = payload.toByteArray(Charsets.UTF_8).size
        require(bytes <= MAX_PAYLOAD_BYTES) {
            "a notification on $channel carries at most $MAX_PAYLOAD_BYTES bytes, and this one is $bytes: send an id"
        }
        connection.prepareStatement("select pg_notify(?, ?)").use { statement ->
            statement.setString(1, channel)
            statement.setString(2, payload)
            statement.executeQuery().close()
        }
    }

    // LISTEN takes no bound parameter, and folds an unquoted name to lower case where pg_notify does not.
    private fun checked(channel: String) {
        require(CHANNEL.matches(channel)) {
            "a channel is a lower-case identifier of at most 63 characters, such as bookings_live, " +
                "and '$channel' is not"
        }
    }

    private val CHANNEL = Regex("[a-z_][a-z0-9_]{0,62}")

    // Equal jitter: each delay is between half and all of one that doubles from a second, up to thirty.
    private val RECONNECT: Schedule<Throwable, Duration> = Schedule.exponential<Throwable>(1.seconds)
        .delayed { _, delay -> minOf(delay, 30.seconds) }
        .jittered(min = 0.5, max = 1.0)

    // Postgres's limit is 8,000 bytes, its terminating zero included.
    private const val MAX_PAYLOAD_BYTES = 7_999
}

/**
 * One run's listening: its connection while it has one, the schedule its next reconnect follows, and what its
 * last read heard that the stream has not taken.
 */
private class Listening(
    private val source: DataSource,
    private val channels: Set<String>,
    private val reconnect: Schedule<Throwable, Any?>,
    private val time: Clock,
    private val onListening: () -> Unit,
    private val onDisconnected: () -> Unit,
) {
    private val heard = ArrayDeque<Notice>()
    private var connected: Pair<Connection, Driver>? = null
    private var retry: ScheduleStep<Throwable, Any?> = reconnect.step
    private var lost = false

    @Volatile
    private var woken = false

    @Volatile
    private var resting: Thread? = null

    /** The next notification, or null once woken to stop. */
    fun next(): Notice? {
        while (heard.isEmpty()) {
            if (woken) return null
            val (connection, driver) = connected ?: connect() ?: continue
            try {
                heard.addAll(driver.notifications(connection, WAIT_MILLIS))
            } catch (failed: SQLException) {
                close()
                lost = true
                counter("postgres.notify.reconnects").increment()
                logWarn("surefold-postgres-notify: lost the connection listening on $named, and will listen again")
                rest(failed)
            }
        }
        return heard.removeFirst()
    }

    /** Ends a read within [WAIT_MILLIS], or a rest at once, so the connection is never closed under a read. */
    fun wake() {
        woken = true
        resting?.interrupt()
    }

    fun close() {
        val subscription = connected ?: return
        connected = null
        resourceScope {
            onRelease { onDisconnected() }
            subscription.first.close()
        }
    }

    private val named: String get() = channels.joinToString()

    /** A connection listening on every channel, or null once a failed attempt has rested. */
    // Whatever else listening throws, the connection it took goes back before the stream fails with it.
    @Suppress("TooGenericExceptionCaught")
    private fun connect(): Pair<Connection, Driver>? {
        val connection = try {
            source.connection
        } catch (failed: SQLException) {
            rest(failed)
            return null
        }
        val subscription = try {
            val driver = Driver.of(connection)
            connection.autoCommit = true
            connection.createStatement().use { statement -> channels.forEach { statement.execute("listen $it") } }
            connection to driver
        } catch (failed: SQLException) {
            connection.close()
            rest(failed)
            return null
        } catch (other: Throwable) {
            connection.close()
            throw other
        }
        connected = subscription
        // Caller defects, including SQLException, must not be mistaken for a database disconnection.
        onListening()
        logInfo("surefold-postgres-notify: listening on $named")
        if (lost) heard.addLast(Notice.Gap)
        lost = false
        retry = reconnect.step
        return subscription
    }

    /** Waits out [failed]'s delay, or throws it once [reconnect] stops; a wake ends the wait. */
    private fun rest(failed: SQLException) {
        when (val decision = retry(failed)) {
            is Schedule.Decision.Done -> throw failed

            is Schedule.Decision.Continue -> {
                retry = decision.step
                resting = Thread.currentThread()
                try {
                    // Set before woken is read, so a wake either sees this thread or is seen here.
                    if (!woken) time.sleep(decision.delay)
                } catch (interrupted: InterruptedException) {
                    // A wake's interrupt ends the rest; any other ends the run.
                    if (!woken) throw interrupted
                } finally {
                    resting = null
                }
            }
        }
    }

    private companion object {
        // How long one read waits for a notification, and so how long a stop waits for the read.
        const val WAIT_MILLIS = 250
    }
}

/** The Postgres driver's notifications, found by `unwrap` and reflection so this module names no driver. */
private class Driver(private val pg: Class<*>, notification: Class<*>) {

    private val read: Method = pg.getMethod("getNotifications", Int::class.javaPrimitiveType)
    private val name: Method = notification.getMethod("getName")
    private val parameter: Method = notification.getMethod("getParameter")
    private val pid: Method = notification.getMethod("getPID")

    /** What [connection] heard within [millis], none if nothing. */
    fun notifications(connection: Connection, millis: Int): List<Notice> = unreflected {
        val received = read.invoke(connection.unwrap(pg), millis) as Array<*>?
        received.orEmpty().map { notification ->
            Notice.Received(
                channel = name.invoke(notification) as String,
                payload = parameter.invoke(notification) as String,
                pid = pid.invoke(notification) as Int,
            )
        }
    }

    companion object {
        /** The driver behind [connection], or a refusal naming the connection that is not Postgres's. */
        fun of(connection: Connection): Driver {
            val pg = loaded(connection, "org.postgresql.PGConnection")
            val notification = loaded(connection, "org.postgresql.PGNotification")
            check(pg != null && notification != null && connection.isWrapperFor(pg)) {
                "PostgresNotifications.listen needs the Postgres JDBC driver, and got a ${connection.javaClass.name}"
            }
            return Driver(pg, notification)
        }

        private fun loaded(connection: Connection, name: String): Class<*>? = try {
            Class.forName(name, false, connection.javaClass.classLoader)
        } catch (_: ClassNotFoundException) {
            null
        }

        /** What [call] answers, throwing the driver's own exception rather than reflection's wrapper around it. */
        private fun <A> unreflected(call: () -> A): A = try {
            call()
        } catch (thrown: InvocationTargetException) {
            throw thrown.cause ?: thrown
        }
    }
}
