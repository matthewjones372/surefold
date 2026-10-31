package io.github.matthewjones372.surefold.jdbc.otel

import com.zaxxer.hikari.HikariDataSource
import io.github.matthewjones372.surefold.Resource
import io.github.matthewjones372.surefold.actor.journal.jdbc.Postgres
import io.github.matthewjones372.surefold.junit.SharedSuite
import io.github.matthewjones372.surefold.junit.expect
import io.github.matthewjones372.surefold.resource
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.samplers.Sampler
import java.sql.Connection
import javax.sql.DataSource
import kotlin.time.TimeSource

/** Parser/wrapper diagnostics; opt in explicitly and keep their numbers outside the workload gate. */
object JdbcSqlDiagnosticsSuite : SharedSuite<DataSource>(concurrent = false) {
    override val shared: Resource<DataSource> = resource {
        val pool = install({ Postgres.empty() as HikariDataSource }) { value, _ -> value.close() }
        pool.connection.use { connection ->
            connection.createStatement().use {
                it.execute("CREATE TABLE bench_rows(id BIGINT PRIMARY KEY, value BIGINT NOT NULL)")
                it.execute("INSERT INTO bench_rows VALUES(1, 0)")
            }
        }
        pool
    }

    init {
        if (System.getProperty("surefold.jdbc.diagnostics") == "true") {
            listOf("repeated", "distinct", "prepared-batch").forEach { shape ->
                test("diagnostic JDBC $shape SQL with and without tracing") { pool ->
                    SdkTracerProvider.builder().setSampler(Sampler.alwaysOn()).build().use { provider ->
                        val telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build()
                        val enabled = pool.traced(telemetry, identifiers = setOf("bench_rows"))
                        val repetitions = System.getProperty("surefold.jdbc.diagnostic.sql.iterations", "2000").toInt()
                        require(repetitions > 0)
                        var generation = 0
                        repeat(5) { round ->
                            val modes = if (round % 2 == 0) listOf("original" to pool, "recording" to enabled)
                            else listOf("recording" to enabled, "original" to pool)
                            modes.forEach { (mode, source) ->
                                val base = generation++ * (repetitions + 100)
                                source.connection.use { connection ->
                                    repeat(100) { connection.diagnosticSql(shape, base + it) }
                                    val mark = TimeSource.Monotonic.markNow()
                                    repeat(repetitions) { connection.diagnosticSql(shape, base + it + 100) }
                                    val nanos = mark.elapsedNow().inWholeNanoseconds
                                    println(
                                        """{"diagnostic":"sql","shape":"$shape","round":$round,"mode":"$mode",""" +
                                            """"operations":$repetitions,"elapsed_nanos":$nanos,""" +
                                            """"nanos_per_operation":${nanos.toDouble() / repetitions}}""",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Connection.diagnosticSql(shape: String, index: Int) {
    if (shape == "prepared-batch") {
        prepareStatement("UPDATE bench_rows SET value=value+1 WHERE id=?").use { statement ->
            repeat(8) {
                statement.setLong(1, 1)
                statement.addBatch()
            }
            val counts = statement.executeBatch()
            expect(counts.size == 8 && counts.all { it == 1 })
        }
    } else {
        val sql = if (shape == "repeated") "SELECT value FROM bench_rows WHERE id=1"
        else "SELECT value FROM bench_rows WHERE id=1 AND $index=$index"
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                expect(rows.next())
                expect(rows.getLong(1) >= 0)
            }
        }
    }
}
