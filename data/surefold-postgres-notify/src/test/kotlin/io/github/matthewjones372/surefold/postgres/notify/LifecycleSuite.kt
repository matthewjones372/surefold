package io.github.matthewjones372.surefold.postgres.notify

import dev.surefold.Promise
import dev.surefold.Resource
import dev.surefold.Schedule
import dev.surefold.Shared
import dev.surefold.TestClock
import dev.surefold.await
import dev.surefold.awaitValue
import dev.surefold.clock
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBe
import dev.surefold.resource
import dev.surefold.scope
import dev.surefold.test.eventually
import dev.surefold.use
import io.github.matthewjones372.surefold.actor.journal.jdbc.Postgres
import io.github.matthewjones372.surefold.stream.Exit
import io.github.matthewjones372.surefold.stream.Forks
import io.github.matthewjones372.surefold.stream.runFold
import io.github.matthewjones372.surefold.stream.start
import io.github.matthewjones372.surefold.stream.take
import org.postgresql.PGConnection
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

object LifecycleSuite : SharedSuite<DataSource>() {
    override val shared: Resource<DataSource> = resource { PGSimpleDataSource().apply { setUrl(Postgres.emptyUrl()) } }

    init {
        test("lifecycle observation is additive beside the existing JVM listen overload") { _ ->
            val methods = PostgresNotifications::class.java.methods
            methods.filter { it.name == "listen" }.map { it.parameterCount }.sorted() shouldBe listOf(3, 5)
            methods.filter { it.name == "listen\$default" }.map { it.parameterCount }.sorted() shouldBe listOf(6, 8)
        }

        test("all channels listen before readiness and release follows the final notification") { source ->
            val tracked = Tracked(source)
            val events = Shared(emptyList<String>())
            val subscribed = Shared(emptyList<String>())
            val released = Shared(false)
            resource {
                install({
                    PostgresNotifications.listen(
                        tracked, setOf("life_first", "life_second"),
                        onListening = {
                            val channels = tracked.current.peek()!!.channels()
                            subscribed.update { channels }
                            events.update { it + "listening" }
                        },
                        onDisconnected = {
                            val closed = tracked.current.peek()!!.isClosed
                            released.update { closed }
                            events.update { it + "disconnected" }
                        },
                    ).take(1).runFold(Unit) { _, _ -> events.update { it + "received" } }.start(Forks())
                }) { run, _ -> run.close() }
            } use { run ->
                eventually(2.seconds) { subscribed.peek().sorted() shouldBe listOf("life_first", "life_second") }
                source.sent("life_second")
                eventually(2.seconds) { expect(run.exit.toCompletableFuture().isDone) }
                run.exit.await() shouldBe Exit.Done(Unit)
            }
            events.peek() shouldBe listOf("listening", "received", "disconnected")
            released.peek() shouldBe true
        }

        test("a lost subscription disconnects once then readies before its one gap and later notification") { source ->
            val tracked = Tracked(source)
            val time = TestClock()
            val events = Shared(emptyList<String>())
            resource {
                install({
                    clock.locally(time) {
                        PostgresNotifications.listen(
                            tracked, setOf("life_reconnect"), reconnect = Schedule.spaced(1.seconds),
                            onListening = { events.update { it + "listening" } },
                            onDisconnected = { events.update { it + "disconnected" } },
                        )
                    }.take(2).runFold(Unit) { _, notice ->
                        events.update { it + if (notice == Notice.Gap) "gap" else "received" }
                    }.start(Forks())
                }) { run, _ -> run.close() }
            } use { run ->
                eventually(2.seconds) { events.peek() shouldBe listOf("listening") }
                source.terminate(tracked.current.peek()!!.unwrap(PGConnection::class.java).backendPID)
                eventually(2.seconds) { time.sleepers() shouldBe 1 }
                events.peek() shouldBe listOf("listening", "disconnected")
                time.adjust(1.seconds)
                eventually(2.seconds) { events.peek().last() shouldBe "gap" }
                source.sent("life_reconnect")
                eventually(2.seconds) { expect(run.exit.toCompletableFuture().isDone) }
            }
            events.peek() shouldBe listOf("listening", "disconnected", "listening", "gap", "received", "disconnected")
        }

        test("failed initial connections and statements report no lifecycle or false gap") { source ->
            val attempts = Shared(0)
            val time = TestClock()
            val events = Shared(emptyList<String>())
            val refusing = object : DataSource by source {
                override fun getConnection(): Connection {
                    val attempt = attempts.modify { it + 1 to (it + 1) }
                    if (attempt == 1) throw SQLException("initial connection unavailable")
                    val raw = source.connection
                    if (attempt != 2) return raw
                    return object : Connection by raw {
                        override fun createStatement(): Statement {
                            val statement = raw.createStatement()
                            return object : Statement by statement {
                                override fun execute(sql: String): Boolean = if (sql.endsWith("life_initial_second")) {
                                    statement.execute("select 1 / 0")
                                } else {
                                    statement.execute(sql)
                                }
                            }
                        }
                    }
                }
            }
            resource {
                install({
                    clock.locally(time) {
                        PostgresNotifications.listen(
                            refusing,
                            setOf("life_initial_first", "life_initial_second"),
                            reconnect = Schedule.spaced(1.seconds),
                            onListening = { events.update { it + "listening" } },
                            onDisconnected = { events.update { it + "disconnected" } },
                        )
                    }.take(1).runFold(Unit) { _, notice ->
                        events.update { it + if (notice is Notice.Received) "received" else "gap" }
                    }.start(Forks())
                }) { run, _ -> run.close() }
            } use { run ->
                repeat(2) { index ->
                    eventually(2.seconds) { attempts.peek() shouldBe index + 1; time.sleepers() shouldBe 1 }
                    events.peek() shouldBe emptyList()
                    time.adjust(1.seconds)
                }
                eventually(2.seconds) { events.peek() shouldBe listOf("listening") }
                source.sent("life_initial_second")
                eventually(2.seconds) { expect(run.exit.toCompletableFuture().isDone) }
            }
            attempts.peek() shouldBe 3
            events.peek() shouldBe listOf("listening", "received", "disconnected")
        }

        test("a listening callback SQLException ends the run and releases its subscription once") { source ->
            val tracked = Tracked(source)
            val disconnected = Shared(0)
            val failed = SQLException("callback defect")
            resource {
                install({
                    PostgresNotifications.listen(
                        tracked, setOf("life_ready_defect"),
                        onListening = { throw failed },
                        onDisconnected = { disconnected.update { it + 1 } },
                    ).runFold(Unit) { _, _ -> }.start(Forks())
                }) { run, _ -> run.close() }
            } use { run ->
                eventually(2.seconds) { expect(run.exit.toCompletableFuture().isDone) }
                if (!run.exit.toCompletableFuture().isDone) run.close()
                run.exit.await() shouldBe Exit.Died(failed)
            }
            tracked.attempts.peek() shouldBe 1
            disconnected.peek() shouldBe 1
            expect(tracked.current.peek()!!.isClosed)
        }

        test("a disconnect callback SQLException ends a lost run without reconnecting or calling twice") { source ->
            val tracked = Tracked(source)
            val ready = Promise<Nothing, Unit>()
            val disconnected = Shared(0)
            val failed = SQLException("disconnect callback defect")
            resource {
                install({
                    PostgresNotifications.listen(
                        tracked, setOf("life_disconnect_defect"),
                        onListening = { ready.succeed(Unit) },
                        onDisconnected = { disconnected.update { it + 1 }; throw failed },
                    ).runFold(Unit) { _, _ -> }.start(Forks())
                }) { run, _ -> run.close() }
            } use { run ->
                eventually(2.seconds) { expect(ready.peek() != null) }
                source.terminate(tracked.current.peek()!!.unwrap(PGConnection::class.java).backendPID)
                eventually(2.seconds) { expect(run.exit.toCompletableFuture().isDone) }
                if (!run.exit.toCompletableFuture().isDone) run.close()
                run.exit.await() shouldBe Exit.Died(failed)
            }
            tracked.attempts.peek() shouldBe 1
            disconnected.peek() shouldBe 1
        }

        test("a notification consumer defect releases its established subscription once") { source ->
            val tracked = Tracked(source)
            val ready = Promise<Nothing, Unit>()
            val disconnected = Shared(0)
            val failed = IllegalStateException("consumer defect")
            resource {
                install({
                    PostgresNotifications.listen(
                        tracked, setOf("life_consumer_defect"),
                        onListening = { ready.succeed(Unit) },
                        onDisconnected = { disconnected.update { it + 1 } },
                    ).runFold(Unit) { _, _ -> throw failed }.start(Forks())
                }) { run, _ -> run.close() }
            } use { run ->
                eventually(2.seconds) { expect(ready.peek() != null) }
                source.sent("life_consumer_defect")
                eventually(2.seconds) { expect(run.exit.toCompletableFuture().isDone) }
                if (!run.exit.toCompletableFuture().isDone) run.close()
                run.exit.await() shouldBe Exit.Died(failed)
            }
            disconnected.peek() shouldBe 1
            expect(tracked.current.peek()!!.isClosed)
        }

        test("a failed connection close retains a failing disconnect callback as suppressed") { source ->
            val ready = Promise<Nothing, Unit>()
            val disconnected = Shared(0)
            val closeFailed = SQLException("connection close defect")
            val callbackFailed = IllegalStateException("disconnect callback defect")
            val failing = object : DataSource by source {
                override fun getConnection(): Connection {
                    val connection = source.connection
                    return object : Connection by connection {
                        override fun close() {
                            connection.close()
                            throw closeFailed
                        }
                    }
                }
            }
            val tracked = Tracked(failing)
            resource {
                install({
                    PostgresNotifications.listen(
                        tracked, setOf("life_close_defects"),
                        onListening = { ready.succeed(Unit) },
                        onDisconnected = { disconnected.update { it + 1 }; throw callbackFailed },
                    ).take(1).runFold(Unit) { _, _ -> }.start(Forks())
                }) { run, _ -> run.close() }
            } use { run ->
                eventually(2.seconds) { expect(ready.peek() != null) }
                source.sent("life_close_defects")
                eventually(2.seconds) { expect(run.exit.toCompletableFuture().isDone) }
                if (!run.exit.toCompletableFuture().isDone) run.close()
                run.exit.await() shouldBe Exit.Died(closeFailed)
            }
            closeFailed.suppressed.toList() shouldBe listOf(callbackFailed)
            disconnected.peek() shouldBe 1
            expect(tracked.current.peek()!!.isClosed)
        }

        test("two lifecycle callback defects still release once and end without retry") { source ->
            val tracked = Tracked(source)
            val disconnected = Shared(0)
            resource {
                install({
                    PostgresNotifications.listen(
                        tracked, setOf("life_callback_defects"),
                        onListening = { throw SQLException("listening callback defect") },
                        onDisconnected = {
                            disconnected.update { it + 1 }
                            error("disconnect callback defect")
                        },
                    ).runFold(Unit) { _, _ -> }.start(Forks())
                }) { run, _ -> run.close() }
            } use { run ->
                eventually(2.seconds) { expect(run.exit.toCompletableFuture().isDone) }
                if (!run.exit.toCompletableFuture().isDone) run.close()
                expect(run.exit.await() is Exit.Died)
            }
            tracked.attempts.peek() shouldBe 1
            disconnected.peek() shouldBe 1
            expect(tracked.current.peek()!!.isClosed)
        }

        test("close joins a disconnect callback already in flight") { source ->
            val ready = Promise<Nothing, Unit>()
            val entered = Promise<Nothing, Unit>()
            val release = Promise<Nothing, Unit>()
            val closed = Promise<Nothing, Unit>()
            val stoppingEntered = Promise<Nothing, Unit>()
            resource {
                install({
                    PostgresNotifications.listen(
                        source, setOf("life_join"),
                        onListening = { ready.succeed(Unit) },
                        onDisconnected = { entered.succeed(Unit); release.awaitValue() },
                    ).take(1).runFold(Unit) { _, _ -> }.start(Forks())
                }) { run, _ -> run.close() }
            } use { run ->
                eventually(2.seconds) { expect(ready.peek() != null) }
                source.sent("life_join")
                eventually(2.seconds) { expect(entered.peek() != null) }
                scope {
                    val stopping = async { stoppingEntered.succeed(Unit); run.close(); closed.succeed(Unit) }
                    try {
                        stoppingEntered.awaitValue()
                        closed.peek() shouldBe null
                    } finally {
                        release.succeed(Unit)
                        stopping.await()
                    }
                }
                expect(closed.peek() != null)
            }
        }

        test("close cancels a listening callback then disconnects without a retry") { source ->
            val tracked = Tracked(source)
            val entered = Promise<Nothing, Unit>()
            val ended = Promise<Nothing, Unit>()
            val disconnected = Shared(0)
            resource {
                install({
                    PostgresNotifications.listen(
                        tracked, setOf("life_cancel"),
                        onListening = {
                            entered.succeed(Unit)
                            try {
                                Promise<Nothing, Unit>().awaitValue()
                            } finally {
                                ended.succeed(Unit)
                            }
                        },
                        onDisconnected = { disconnected.update { it + 1 } },
                    ).runFold(Unit) { _, _ -> }.start(Forks())
                }) { run, _ -> run.close() }
            } use { run ->
                eventually(2.seconds) { expect(entered.peek() != null) }
                run.close()
                expect(ended.peek() != null)
                disconnected.peek() shouldBe 1
                tracked.attempts.peek() shouldBe 1
            }
        }
    }
}

private class Tracked(private val source: DataSource) : DataSource by source {
    val current = Shared<Connection?>(null)
    val attempts = Shared(0)

    override fun getConnection(): Connection = source.connection.also { connection ->
        attempts.update { it + 1 }
        current.update { connection }
    }
}

private fun Connection.channels(): List<String> = createStatement().use { statement ->
    statement.executeQuery("select pg_listening_channels()").use { rows ->
        buildList { while (rows.next()) add(rows.getString(1)) }
    }
}

private fun DataSource.sent(channel: String) = connection.use { PostgresNotifications.notify(it, channel, "after") }

private fun DataSource.terminate(pid: Int) = connection.use { connection ->
    connection.prepareStatement("select pg_terminate_backend(?)").use { statement ->
        statement.setInt(1, pid)
        statement.executeQuery().close()
    }
}
