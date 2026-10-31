package io.github.matthewjones372.surefold.jdbc.otel

import com.zaxxer.hikari.HikariDataSource
import io.github.matthewjones372.surefold.Promise
import io.github.matthewjones372.surefold.Resource
import io.github.matthewjones372.surefold.Shared
import io.github.matthewjones372.surefold.actor.journal.jdbc.Postgres
import io.github.matthewjones372.surefold.awaitValue
import io.github.matthewjones372.surefold.junit.SharedSuite
import io.github.matthewjones372.surefold.junit.expect
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldHaveSize
import io.github.matthewjones372.surefold.junit.shouldThrow
import io.github.matthewjones372.surefold.otel.span
import io.github.matthewjones372.surefold.parMap
import io.github.matthewjones372.surefold.resource
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.data.SpanData
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.CancellationException
import javax.sql.DataSource

object JdbcTracingSuite : SharedSuite<DataSource>() {
    override val shared: Resource<DataSource> = resource {
        val pool = install({ Postgres.empty() as HikariDataSource }) { source, _ -> source.close() }
        pool.connection.use { connection ->
            connection.createStatement().use {
                it.execute("CREATE TABLE bookings (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
            }
        }
        pool
    }

    init {
        test("plain and prepared queries derive safe summaries without query wrappers") { pool ->
            pool.seed(101, PRIVATE_MARKER)
            PrivacyRecorder().use { recorder ->
                val database = pool.traced(recorder.sdk, identifiers = setOf("bookings"))
                database.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery(
                            "SELECT value AS \"$PRIVATE_MARKER\" FROM bookings WHERE id=101 /* $PRIVATE_MARKER */",
                        ).use {
                            expect(it.next())
                            it.getString(1) shouldBe PRIVATE_MARKER
                            expect(!it.next())
                        }
                    }
                    connection.prepareStatement("SELECT value FROM bookings WHERE value=?").use { statement ->
                        statement.setString(1, PRIVATE_MARKER)
                        statement.executeQuery().use { expect(it.next()) }
                    }
                }
                recorder.clients().map { it.name } shouldBe listOf(selectSummary(), selectSummary())
                recorder.assertNoMarker(onlyClients = true)
            }
        }

        test("preparing under one parent does not capture it for execution under another") { pool ->
            pool.seed(102)
            PrivacyRecorder().use { recorder ->
                val database = pool.traced(recorder.sdk, identifiers = setOf("bookings"))
                val tracer = recorder.sdk.getTracer("application")
                database.connection.use { connection ->
                    val statement = tracer.span("prepare") {
                        connection.prepareStatement("SELECT value FROM bookings WHERE id=102")
                    }
                    statement.use {
                        tracer.span("execute") { it.executeQuery().use { rows -> expect(rows.next()) } }
                    }
                }
                val execute = recorder.exported.peek().single { it.name == "execute" }
                val query = recorder.clients().single()
                query.parentSpanId shouldBe execute.spanId
                query.traceId shouldBe execute.traceId
                expect(query.spanId != execute.spanId)
            }
        }

        test("a query without a parent has its own root span") { pool ->
            PrivacyRecorder().use { recorder ->
                Context.root().makeCurrent().use {
                    pool.traced(recorder.sdk).readCount(103) shouldBe 0
                }
                val query = recorder.clients().single()
                expect(!query.parentSpanContext.isValid)
                expect(query.spanContext.isValid)
                query.name shouldBe "SELECT"
            }
        }

        test("parMap queries retain the parent request trace") { pool ->
            PrivacyRecorder().use { recorder ->
                val database = pool.traced(recorder.sdk)
                recorder.sdk.getTracer("application").span("request") {
                    parMap(listOf(104, 105, 106)) { database.readCount(it) } shouldBe listOf(0, 0, 0)
                }
                val request = recorder.exported.peek().single { it.name == "request" }
                val queries = recorder.clients()
                queries shouldHaveSize 3
                queries.forEach {
                    it.parentSpanId shouldBe request.spanId
                    it.traceId shouldBe request.traceId
                }
            }
        }

        test("overlapping requests keep their queries in their own traces") { pool ->
            PrivacyRecorder().use { recorder ->
                val database = pool.traced(recorder.sdk)
                val ready = listOf(Promise<Nothing, Unit>(), Promise<Nothing, Unit>())
                val tracer = recorder.sdk.getTracer("application")
                parMap(listOf(0, 1)) { index ->
                    tracer.span("request-$index") {
                        ready[index].succeed(Unit)
                        ready[1 - index].awaitValue()
                        val parent = Span.current().spanContext
                        database.readCount(107 + index) shouldBe 0
                        parent
                    }
                }.forEach { parent ->
                    val queries = recorder.clients().filter { it.traceId == parent.traceId }
                    queries shouldHaveSize 1
                    queries.single().parentSpanId shouldBe parent.spanId
                }
                recorder.clients().map { it.traceId }.distinct() shouldHaveSize 2
            }
        }

        test("statement and prepared batches preserve update counts and trace each execution") { pool ->
            PrivacyRecorder().use { recorder ->
                val database = pool.traced(recorder.sdk, identifiers = setOf("bookings"))
                database.connection.use { connection ->
                    connection.createStatement().use {
                        it.addBatch("INSERT INTO bookings VALUES (109, '$PRIVATE_MARKER')")
                        it.addBatch("INSERT INTO bookings VALUES (110, '$PRIVATE_MARKER')")
                        it.executeBatch().toList() shouldBe listOf(1, 1)
                    }
                    connection.prepareStatement("INSERT INTO bookings VALUES (?, ?)").use {
                        listOf(111, 112).forEach { id ->
                            it.setInt(1, id)
                            it.setString(2, PRIVATE_MARKER)
                            it.addBatch()
                        }
                        it.executeBatch().toList() shouldBe listOf(1, 1)
                    }
                }
                val queries = recorder.clients()
                queries shouldHaveSize 2
                if (selectSummary() == "SELECT") {
                    queries.map { it.name } shouldBe listOf("DB query", "INSERT")
                } else {
                    expect(queries.all { it.name in setOf("BATCH", "INSERT", "INSERT bookings") })
                }
                recorder.assertNoMarker(onlyClients = true)
                listOf(109, 110, 111, 112).forEach { pool.readCount(it) shouldBe 1 }
            }
        }

        test("callable statements preserve output parameters and emit a client span") { pool ->
            PrivacyRecorder().use { recorder ->
                pool.traced(recorder.sdk).connection.use { connection ->
                    connection.prepareCall("{? = call abs(?)}").use {
                        it.registerOutParameter(1, java.sql.Types.INTEGER)
                        it.setInt(2, -113)
                        it.execute()
                        it.getInt(1) shouldBe 113
                    }
                }
                recorder.clients() shouldHaveSize 1
                recorder.assertNoMarker(onlyClients = true)
            }
        }

        test("closing and unwrapping JDBC objects preserves the driver contract") { pool ->
            PrivacyRecorder().use { recorder ->
                val database = pool.traced(recorder.sdk)
                expect(database.isWrapperFor(HikariDataSource::class.java))
                expect(database.unwrap(HikariDataSource::class.java) === pool)
                val connection = database.connection
                try {
                    expect(connection.isWrapperFor(org.postgresql.PGConnection::class.java))
                    expect(connection.unwrap(org.postgresql.PGConnection::class.java) is org.postgresql.PGConnection)
                    val statement = connection.prepareStatement("SELECT 114")
                    val result = statement.executeQuery()
                    expect(result.next())
                    result.getInt(1) shouldBe 114
                    result.close()
                    expect(result.isClosed)
                    statement.close()
                    expect(statement.isClosed)
                } finally {
                    connection.close()
                }
                expect(connection.isClosed)
                expect(!(pool as HikariDataSource).isClosed)
            }
        }

        test("a rollback discards database changes while keeping execution spans") { pool ->
            PrivacyRecorder().use { recorder ->
                pool.traced(recorder.sdk).connection.use { connection ->
                    connection.autoCommit = false
                    connection.prepareStatement("INSERT INTO bookings VALUES (115, ?)").use {
                        it.setString(1, PRIVATE_MARKER)
                        it.executeUpdate() shouldBe 1
                    }
                    connection.rollback()
                }
                pool.readCount(115) shouldBe 0
                recorder.clients() shouldHaveSize 1
                recorder.assertNoMarker(onlyClients = true)
            }
        }

        test("the original statement exception is rethrown without retrying database work") { pool ->
            val attempts = Shared(0)
            val original = SQLException(PRIVATE_MARKER, "22000", 266)
            val source = object : DataSource by pool {
                override fun getConnection(): Connection {
                    val connection = pool.connection
                    return object : Connection by connection {
                        override fun createStatement(): Statement {
                            val statement = connection.createStatement()
                            return object : Statement by statement {
                                override fun executeQuery(sql: String): ResultSet {
                                    attempts.update { it + 1 }
                                    throw original
                                }
                            }
                        }
                    }
                }
            }
            PrivacyRecorder().use { recorder ->
                val actual = sqlFailure {
                    source.traced(recorder.sdk).connection.use { connection ->
                        connection.createStatement().use { it.executeQuery("SELECT 1") }
                    }
                }
                expect(actual === original)
                attempts.peek() shouldBe 1
                recorder.clients() shouldHaveSize 1
                recorder.assertNoMarker(onlyClients = true)
            }
        }

        test("a database error reaches the caller with its details while JDBC telemetry stays private") { pool ->
            pool.seed(116, PRIVATE_MARKER)
            PrivacyRecorder().use { recorder ->
                val failure = sqlFailure {
                    pool.traced(recorder.sdk).connection.use { connection ->
                        connection.createStatement().use {
                            it.executeQuery("SELECT '$PRIVATE_MARKER'::integer /* $PRIVATE_MARKER */")
                        }
                    }
                }
                failure.sqlState shouldBe "22P02"
                expect(failure.message.orEmpty().contains(PRIVATE_MARKER))
                val query = recorder.clients().single()
                query.status.statusCode shouldBe StatusCode.ERROR
                query.status.description shouldBe ""
                query.attributes.get(AttributeKey.stringKey("error.type")) shouldBe "database_error"
                expect(query.events.isEmpty())
                recorder.assertNoMarker(onlyClients = true)
            }
        }

        test("an enclosing application span can still record the original propagated exception") { pool ->
            PrivacyRecorder().use { recorder ->
                sqlFailure {
                    recorder.sdk.getTracer("application").span("request") {
                        pool.traced(recorder.sdk).connection.use { connection ->
                            connection.createStatement().use { it.executeQuery("SELECT '$PRIVATE_MARKER'::integer") }
                        }
                    }
                }
                val request = recorder.exported.peek().single { it.name == "request" }
                expect(request.events.toString().contains(PRIVATE_MARKER))
                val query = recorder.clients().single()
                expect(!query.toString().contains(PRIVATE_MARKER))
                expect(query.events.isEmpty())
                query.parentSpanId shouldBe request.spanId
            }
        }

        test("critical sampler failures propagate through JDBC before a write is attempted") { pool ->
            val cancelled = CancellationException("cancel query")
            PrivacyRecorder(samplingFailure = cancelled).use { recorder ->
                shouldThrow<CancellationException>({ pool.traced(recorder.sdk).insertCritical() }) {
                    expect(it === cancelled)
                }
            }
            val interrupted = InterruptedException("interrupt query")
            PrivacyRecorder(samplingFailure = interrupted).use { recorder ->
                shouldThrow<InterruptedException>({ pool.traced(recorder.sdk).insertCritical() }) {
                    expect(it === interrupted)
                }
            }
            val fatal = LinkageError("fatal telemetry")
            PrivacyRecorder(samplingFailure = fatal).use { recorder ->
                shouldThrow<LinkageError>({ pool.traced(recorder.sdk).insertCritical() }) { expect(it === fatal) }
            }
            pool.readCount(119) shouldBe 0
        }

        test("a no-op SDK still executes queries and telemetry failure never retries the write") { pool ->
            pool.traced(OpenTelemetry.noop()).readCount(117) shouldBe 0
            PrivacyRecorder(samplingFailure = IllegalStateException("broken telemetry")).use { recorder ->
                pool.traced(recorder.sdk).connection.use { connection ->
                    connection.createStatement().use {
                        it.executeUpdate("INSERT INTO bookings VALUES (118, 'written once')") shouldBe 1
                    }
                }
                pool.readCount(118) shouldBe 1
            }
        }
    }
}

private fun PrivacyRecorder.clients(): List<SpanData> = exported.peek().filter { it.kind == SpanKind.CLIENT }

private fun selectSummary(): String = when (System.getProperty("surefold.jdbc.database.conventions", "legacy")) {
    "stable", "dual" -> "SELECT bookings"
    "legacy" -> "SELECT"
    else -> error("unknown JDBC convention test mode")
}

private fun DataSource.seed(id: Int, value: String = "booking") {
    connection.use { connection ->
        connection.prepareStatement("INSERT INTO bookings VALUES (?, ?)").use {
            it.setInt(1, id)
            it.setString(2, value)
            it.executeUpdate() shouldBe 1
        }
    }
}

private fun DataSource.readCount(id: Int): Int = connection.use { connection ->
    connection.prepareStatement("SELECT COUNT(*) FROM bookings WHERE id=?").use {
        it.setInt(1, id)
        it.executeQuery().use { result ->
            expect(result.next())
            result.getInt(1)
        }
    }
}

private fun sqlFailure(block: () -> Unit): SQLException = try {
    block()
    error("expected the database operation to fail")
} catch (failure: SQLException) {
    failure
}

private fun DataSource.insertCritical() {
    connection.use { connection ->
        connection.createStatement().use { it.executeUpdate("INSERT INTO bookings VALUES (119, 'must not execute')") }
    }
}
