package io.github.matthewjones372.surefold.jdbc.otel

import io.github.matthewjones372.surefold.Shared
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.expect
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldThrow
import io.github.matthewjones372.surefold.otel.span
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.ConnectionBuilder
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLType
import java.sql.ShardingKey
import java.sql.ShardingKeyBuilder
import java.sql.Statement
import java.util.Properties
import javax.sql.DataSource

object JdbcDefaultMethodsSuite : Suite() {
    init {
        test("connection request and sharding methods reach the original driver with nullable arguments") {
            val driver = DefaultMethodsDriver()
            driver.source.traced(OpenTelemetry.noop()).connection.use { connection ->
                connection.beginRequest()
                connection.endRequest()
                connection.setShardingKeyIfValid(null, null, 17) shouldBe true
                connection.setShardingKeyIfValid(null, 19) shouldBe false
                connection.setShardingKey(null, null)
                connection.setShardingKey(null)
            }
            driver.calls.peek() shouldBe listOf(
                "begin", "end", "valid-two:17", "valid-one:19", "set-two", "set-one",
            )
        }
        test("connection default-method exceptions retain their original identity") {
            val failure = SQLException("request failure")
            val driver = DefaultMethodsDriver(requestFailure = failure)
            driver.source.traced(OpenTelemetry.noop()).connection.use { connection ->
                shouldThrow<SQLException>({ connection.beginRequest() }) { expect(it === failure) }
            }
        }
        test("data source sharding builders preserve nullable inputs and their original result") {
            val driver = DefaultMethodsDriver()
            val builder = driver.source.traced(OpenTelemetry.noop()).createShardingKeyBuilder()
            expect(builder.subkey(null, null) === builder)
            expect(builder.build() === driver.key)
            driver.calls.peek() shouldBe listOf("sharding-builder", "subkey", "build-key")
        }
        test("connection builders forward nullable configuration and trace execution under its current parent") {
            val driver = DefaultMethodsDriver()
            PrivacyRecorder().use { recorder ->
                val database = driver.source.traced(recorder.sdk)
                val tracer = recorder.sdk.getTracer("application")
                val builder = tracer.span("configure") {
                    database.createConnectionBuilder().also {
                        expect(it.user(null) === it)
                        expect(it.password(null) === it)
                        expect(it.shardingKey(null) === it)
                        expect(it.superShardingKey(null) === it)
                    }
                }
                builder.build().use { connection ->
                    tracer.span("execute") {
                        connection.createStatement().use { statement ->
                            statement.executeQuery("SELECT 266").use { result ->
                                expect(result.next())
                                result.getInt(1) shouldBe 266
                                expect(!result.next())
                            }
                        }
                    }
                }
                val execute = recorder.exported.peek().single { it.name == "execute" }
                val query = recorder.exported.peek().single { it.kind == SpanKind.CLIENT }
                query.parentSpanId shouldBe execute.spanId
                query.traceId shouldBe execute.traceId
                query.name shouldBe "SELECT"
                driver.calls.peek() shouldBe listOf(
                    "connection-builder", "user", "password", "sharding", "super-sharding", "build-connection",
                )
                recorder.assertNoMarker(onlyClients = true)
            }
        }
        test("connection builders follow new delegate instances returned by fluent methods") {
            val driver = DefaultMethodsDriver()
            val source = object : DataSource by driver.source {
                override fun createConnectionBuilder(): ConnectionBuilder =
                    ImmutableDefaultConnectionBuilder(driver.connection)
            }
            source.traced(OpenTelemetry.noop()).createConnectionBuilder()
                .user(null).password(null).shardingKey(null).superShardingKey(null)
                .build().use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT 266").use { result ->
                            expect(result.next())
                            result.getInt(1) shouldBe 266
                        }
                    }
                }
        }
        test("connection builder acquisition failures retain their original identity without retry") {
            val failure = SQLException("builder acquisition failure")
            val driver = DefaultMethodsDriver(buildFailure = failure)
            val builder = driver.source.traced(OpenTelemetry.noop()).createConnectionBuilder()
            shouldThrow<SQLException>({ builder.build() }) { expect(it === failure) }
            driver.calls.peek() shouldBe listOf("connection-builder", "build-connection")
        }
    }
}

private class DefaultMethodsDriver(
    private val requestFailure: SQLException? = null,
    private val buildFailure: SQLException? = null,
) {
    val calls = Shared(emptyList<String>())
    val key = object : ShardingKey {}
    private val metadata = defaultsFake(DatabaseMetaData::class.java) { method ->
        when (method.name) {
            "getURL" -> "jdbc:postgresql://localhost:5432/database"
            else -> error("unexpected metadata call: ${method.name}")
        }
    }
    private val unsupportedConnection = defaultsFake(Connection::class.java) { method ->
        when (method.name) {
            "close" -> null
            "isWrapperFor" -> false
            else -> error("unexpected connection call: ${method.name}")
        }
    }
    val connection: Connection = object : Connection by unsupportedConnection {
        override fun getMetaData(): DatabaseMetaData = metadata
        override fun getClientInfo(): Properties = Properties()
        override fun beginRequest() { note("begin"); requestFailure?.let { throw it } }
        override fun endRequest() { note("end") }
        override fun setShardingKeyIfValid(
            shardingKey: ShardingKey?,
            superShardingKey: ShardingKey?,
            timeout: Int,
        ): Boolean {
            expect(shardingKey == null && superShardingKey == null)
            note("valid-two:$timeout")
            return true
        }
        override fun setShardingKeyIfValid(shardingKey: ShardingKey?, timeout: Int): Boolean {
            expect(shardingKey == null)
            note("valid-one:$timeout")
            return false
        }
        override fun setShardingKey(shardingKey: ShardingKey?, superShardingKey: ShardingKey?) {
            expect(shardingKey == null && superShardingKey == null)
            note("set-two")
        }
        override fun setShardingKey(shardingKey: ShardingKey?) { expect(shardingKey == null); note("set-one") }
        override fun createStatement(): Statement = statement()
    }
    private val unsupportedSource = defaultsFake(DataSource::class.java) { method ->
        when (method.name) {
            "isWrapperFor" -> false
            else -> error("unexpected source call: ${method.name}")
        }
    }
    val source: DataSource = object : DataSource by unsupportedSource {
        override fun getConnection(): Connection = this@DefaultMethodsDriver.connection
        override fun getConnection(username: String?, password: String?): Connection =
            this@DefaultMethodsDriver.connection
        override fun createConnectionBuilder(): ConnectionBuilder {
            note("connection-builder")
            return object : ConnectionBuilder {
                override fun user(username: String?): ConnectionBuilder = apply {
                    expect(username == null)
                    note("user")
                }
                override fun password(password: String?): ConnectionBuilder = apply {
                    expect(password == null)
                    note("password")
                }
                override fun shardingKey(shardingKey: ShardingKey?): ConnectionBuilder = apply {
                    expect(shardingKey == null)
                    note("sharding")
                }
                override fun superShardingKey(superShardingKey: ShardingKey?): ConnectionBuilder = apply {
                    expect(superShardingKey == null)
                    note("super-sharding")
                }
                override fun build(): Connection {
                    note("build-connection")
                    buildFailure?.let { throw it }
                    return this@DefaultMethodsDriver.connection
                }
            }
        }
        override fun createShardingKeyBuilder(): ShardingKeyBuilder {
            note("sharding-builder")
            return object : ShardingKeyBuilder {
                override fun subkey(subkey: Any?, subkeyType: SQLType?): ShardingKeyBuilder = apply {
                    expect(subkey == null && subkeyType == null)
                    note("subkey")
                }
                override fun build(): ShardingKey { note("build-key"); return key }
            }
        }
    }
    private fun note(value: String) { calls.update { it + value } }
    private fun statement(): Statement {
        val rows = Shared(0)
        val result = defaultsFake(ResultSet::class.java) { method ->
            when (method.name) {
                "next" -> { rows.update { it + 1 }; rows.peek() == 1 }
                "getInt" -> 266
                "close" -> null
                else -> error("unexpected result call: ${method.name}")
            }
        }
        return defaultsFake(Statement::class.java) { method ->
            when (method.name) {
                "executeQuery" -> result
                "getConnection" -> connection
                "close" -> null
                "isWrapperFor" -> false
                else -> error("unexpected statement call: ${method.name}")
            }
        }
    }
}

private class ImmutableDefaultConnectionBuilder(
    private val connection: Connection,
    private val configured: List<String> = emptyList(),
) : ConnectionBuilder {
    override fun user(username: String?): ConnectionBuilder {
        expect(username == null)
        return next("user")
    }
    override fun password(password: String?): ConnectionBuilder {
        expect(password == null)
        return next("password")
    }
    override fun shardingKey(shardingKey: ShardingKey?): ConnectionBuilder {
        expect(shardingKey == null)
        return next("sharding")
    }
    override fun superShardingKey(superShardingKey: ShardingKey?): ConnectionBuilder {
        expect(superShardingKey == null)
        return next("super-sharding")
    }
    override fun build(): Connection {
        configured shouldBe listOf("user", "password", "sharding", "super-sharding")
        return connection
    }
    private fun next(name: String): ConnectionBuilder =
        ImmutableDefaultConnectionBuilder(connection, configured + name)
}

private fun <A> defaultsFake(type: Class<A>, invoke: (Method) -> Any?): A = type.cast(
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> invoke(method) },
)
