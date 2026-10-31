package io.github.matthewjones372.surefold.jdbc.otel

import io.github.matthewjones372.surefold.Shared
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.expect
import io.github.matthewjones372.surefold.junit.shouldBe
import io.opentelemetry.api.OpenTelemetry
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.SQLException
import javax.sql.DataSource

object JdbcTracingSetupSuite : Suite() {
    init {
        test("disabled returns the exact source before touching telemetry or invalid identifiers") {
            val sourceCalls = Shared(0)
            val telemetryCalls = Shared(0)
            val source = interfaceFake(DataSource::class.java) { _ ->
                sourceCalls.update { it + 1 }
                error("disabled tracing must not touch its source")
            }
            val telemetry = interfaceFake(OpenTelemetry::class.java) { _ ->
                telemetryCalls.update { it + 1 }
                error("disabled tracing must not touch telemetry")
            }
            val identifiers = setOf(PRIVATE_MARKER, "UPPER", "qualified.name") + (0..256).map { "table_$it" }
            expect(source.traced(telemetry, enabled = false, identifiers = identifiers) === source)
            sourceCalls.peek() shouldBe 0
            telemetryCalls.peek() shouldBe 0
        }

        test("enabled wrapping does not acquire a connection and preserves the acquisition exception") {
            val acquired = Shared(0)
            val original = SQLException("original connection failure", "08001", 419)
            val source = interfaceFake(DataSource::class.java) { method ->
                when (method.name) {
                    "getConnection" -> {
                        acquired.update { it + 1 }
                        throw original
                    }

                    "isWrapperFor" -> false

                    else -> error("unexpected source call: ${method.name}")
                }
            }
            val traced = source.traced(OpenTelemetry.noop())
            acquired.peek() shouldBe 0
            try {
                traced.connection
                error("expected the connection acquisition failure")
            } catch (actual: SQLException) {
                expect(actual === original)
            }
            acquired.peek() shouldBe 1
        }
    }
}

private fun <A> interfaceFake(type: Class<A>, invoke: (Method) -> Any?): A = type.cast(
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> invoke(method) },
)
