package io.github.matthewjones372.surefold.jdbc.otel

import io.github.matthewjones372.surefold.Shared
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.expect
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldThrow
import io.opentelemetry.api.OpenTelemetry
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.CancellationException
import javax.sql.DataSource

object JdbcMetadataFailureSuite : Suite() {
    init {
        test("metadata cancellation is preserved when acquiring a traced connection") {
            assertMetadataFailure(CancellationException("metadata cancellation"))
        }
        test("metadata interruption is preserved when acquiring a traced connection") {
            assertMetadataFailure(InterruptedException("metadata interruption"))
        }
        test("metadata fatal errors are preserved when acquiring a traced connection") {
            assertMetadataFailure(LinkageError("metadata fatal error"))
        }
        test("client property callback cancellation is preserved when acquiring a traced connection") {
            assertClientPropertyFailure(CancellationException("client property cancellation"))
        }
        test("client property callback interruption is preserved when acquiring a traced connection") {
            assertClientPropertyFailure(InterruptedException("client property interruption"))
        }
        test("client property callback fatal errors are preserved when acquiring a traced connection") {
            assertClientPropertyFailure(LinkageError("client property fatal error"))
        }
        test("the metadata guard disengages before the caller receives its connection") {
            val reads = Shared(0)
            val original = CancellationException("caller cancellation")
            val source = metadataSource(read = {
                reads.update { it + 1 }
                if (reads.peek() > 1) throw original
                Properties()
            })
            source.traced(OpenTelemetry.noop()).connection.use { connection ->
                shouldThrow<CancellationException>({ connection.clientInfo }) { expect(it === original) }
            }
            reads.peek() shouldBe 2
        }
        test("connection credentials reach the original source exactly once") {
            val calls = Shared(0)
            val source = metadataSource(acquire = { username, password ->
                username shouldBe "test-user"
                password shouldBe "test-password"
                calls.update { it + 1 }
            })
            source.traced(OpenTelemetry.noop()).getConnection("test-user", "test-password").use { }
            calls.peek() shouldBe 1
        }
        test("null credentials are delegated without adding a parameter check") {
            val calls = Shared(0)
            val source = metadataSource(acquire = { username, password ->
                username shouldBe null
                password shouldBe null
                calls.update { it + 1 }
            })
            source.traced(OpenTelemetry.noop()).getConnection(null, null).use { }
            calls.peek() shouldBe 1
        }
        test("the shared instrumentation source keeps its metadata cache between borrows") {
            val reads = Shared(0)
            val source = metadataSource(read = { reads.update { it + 1 }; Properties() })
                .traced(OpenTelemetry.noop())
            source.connection.use { }
            source.connection.use { }
            reads.peek() shouldBe 1
        }
        test("cleanup failure is suppressed onto the preserved metadata failure") {
            val original = LinkageError("metadata failure")
            val cleanup = SQLException("close failure")
            val closes = Shared(0)
            val source = metadataSource(read = { throw original }, close = {
                closes.update { it + 1 }
                throw cleanup
            })
            shouldThrow<LinkageError>({ source.traced(OpenTelemetry.noop()).connection }) {
                expect(it === original)
                it.suppressed.toList() shouldBe listOf(cleanup)
            }
            closes.peek() shouldBe 1
        }
        test("a failure before the guarded metadata read also closes the borrowed connection") {
            val original = LinkageError("metadata URL failure")
            val closes = Shared(0)
            val source = metadataSource(url = { throw original }, close = { closes.update { it + 1 } })
            shouldThrow<LinkageError>({ source.traced(OpenTelemetry.noop()).connection }) { expect(it === original) }
            closes.peek() shouldBe 1
        }
    }
}

private inline fun <reified T : Throwable> assertMetadataFailure(failure: T) {
    val reads = Shared(0)
    val closes = Shared(0)
    val source = metadataSource(read = { reads.update { it + 1 }; throw failure }, close = { closes.update { it + 1 } })
    shouldThrow<T>({ source.traced(OpenTelemetry.noop()).connection.use { } }) { expect(it === failure) }
    reads.peek() shouldBe 1
    closes.peek() shouldBe 1
}

private fun metadataSource(
    read: () -> Properties = { Properties() },
    close: () -> Unit = {},
    url: () -> String = { "jdbc:postgresql://localhost:5432/database" },
    acquire: (String?, String?) -> Unit = { _, _ -> },
): DataSource {
    val metadata = metadataFake(DatabaseMetaData::class.java) { method ->
        when (method.name) {
            "getURL" -> url()
            else -> error("unexpected metadata call: ${method.name}")
        }
    }
    val unsupported = metadataFake(Connection::class.java) { method ->
        when (method.name) {
            "isWrapperFor" -> false
            "close" -> { close(); null }
            else -> error("unexpected connection call: ${method.name}")
        }
    }
    val connection = object : Connection by unsupported {
        override fun getMetaData(): DatabaseMetaData = metadata
        override fun getClientInfo(): Properties = read()
    }
    val unsupportedSource = metadataFake(DataSource::class.java) { method ->
        when (method.name) {
            "isWrapperFor" -> false
            else -> error("unexpected source call: ${method.name}")
        }
    }
    return object : DataSource by unsupportedSource {
        override fun getConnection(): Connection { acquire(null, null); return connection }
        override fun getConnection(username: String?, password: String?): Connection {
            acquire(username, password)
            return connection
        }
    }
}

private fun <A> metadataFake(type: Class<A>, invoke: (Method) -> Any?): A = type.cast(
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> invoke(method) },
)

private inline fun <reified T : Throwable> assertClientPropertyFailure(failure: T) {
    val reads = Shared(0)
    val callbacks = Shared(0)
    val closes = Shared(0)
    val properties = object : Properties() {
        override fun getProperty(key: String): String? {
            callbacks.update { it + 1 }
            throw failure
        }
    }
    val source = metadataSource(
        read = { reads.update { it + 1 }; properties },
        close = { closes.update { it + 1 } },
    )
    shouldThrow<T>({ source.traced(OpenTelemetry.noop()).connection.use { } }) { expect(it === failure) }
    reads.peek() shouldBe 1
    expect(callbacks.peek() > 0)
    closes.peek() shouldBe 1
}
