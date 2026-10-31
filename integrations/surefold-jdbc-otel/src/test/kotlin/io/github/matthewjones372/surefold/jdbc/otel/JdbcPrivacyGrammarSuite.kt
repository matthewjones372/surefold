package io.github.matthewjones372.surefold.jdbc.otel

import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.expect
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldThrow
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes

object JdbcPrivacyGrammarSuite : Suite() {
    init {
        test("approved identifiers are copied and invalid configuration has one input-free error") {
            val failures = listOf(
                setOf(PRIVATE_MARKER), setOf(""), setOf("Bookings"), setOf("schema.bookings"),
                setOf("a".repeat(64)), (0..256).map { "table_$it" }.toSet(),
            ).map { invalid ->
                var message: String? = null
                shouldThrow<IllegalArgumentException>({ JdbcPrivacyTelemetry(OpenTelemetry.noop(), invalid) }) {
                    message = it.message
                }
                message
            }
            failures.toSet().size shouldBe 1
            expect(!failures.first().orEmpty().contains(PRIVATE_MARKER))
            val configured = mutableSetOf("bookings")
            PrivacyRecorder(configured).use { recording ->
                configured.clear()
                configured += "payments"
                recording.tracer.spanBuilder(PRIVATE_MARKER)
                    .setAttribute("db.query.summary", "SELECT bookings").startSpan().end()
                recording.exported.peek().single().name shouldBe "SELECT bookings"
            }
            JdbcPrivacyTelemetry(OpenTelemetry.noop(), (0..255).map { "table_$it" }.toSet())
        }

        test("every allowed operation accepts its exact bare and approved detailed summary") {
            val operations = listOf(
                "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "CALL", "CREATE", "ALTER", "DROP", "TRUNCATE", "BATCH",
            )
            val longest = "a".repeat(63)
            PrivacyRecorder(setOf("bookings", longest)).use { recording ->
                val summaries = operations.flatMap { listOf(it, "$it bookings") } + "TRUNCATE $longest"
                summaries.forEach { summary ->
                    recording.tracer.spanBuilder(PRIVATE_MARKER)
                        .setAttribute("db.query.summary", summary).startSpan().end()
                }
                recording.sampled.peek().map { it.name } shouldBe summaries
                recording.exported.peek().map { it.name } shouldBe summaries
                recording.exported.peek().forEach { span ->
                    span.attributes.get(AttributeKey.stringKey("db.query.summary")) shouldBe span.name
                    expect(span.name.length <= 72)
                    expect(span.name.all { it.code < 128 })
                }
                recording.assertNoMarker()
            }
        }

        test("unsafe whole summaries fall back to an exact operation without parsing SQL or initial names") {
            val cases = listOf(
                Triple("SELECT unapproved", null, "SELECT"),
                Triple("SELECT bookings JOIN payments", null, "SELECT"),
                Triple("SELECT bookings,payments", null, "SELECT"),
                Triple("SELECT schema.bookings", null, "SELECT"),
                Triple("SELECT \"bookings\"", null, "SELECT"),
                Triple("SELECT  bookings", null, "SELECT"),
                Triple("SELECT\tbookings", null, "DB query"),
                Triple("SELECT\nbookings", null, "DB query"),
                Triple(" SELECT bookings", null, "DB query"),
                Triple("select bookings", null, "DB query"),
                Triple("WITH bookings", null, "DB query"),
                Triple(PRIVATE_MARKER, "UPDATE", "UPDATE"),
                Triple(PRIVATE_MARKER, "UPDATE bookings", "DB query"),
                Triple(PRIVATE_MARKER, "UPDATE ", "DB query"),
                Triple(PRIVATE_MARKER, "update", "DB query"),
            )
            PrivacyRecorder().use { recording ->
                cases.forEach { (summary, operation, _) ->
                    val builder = recording.tracer.spanBuilder("DELETE bookings $PRIVATE_MARKER")
                        .setAttribute("db.query.summary", summary)
                        .setAttribute("db.query.text", "SELECT bookings")
                    if (operation != null) builder.setAttribute("db.operation.name", operation)
                    builder.startSpan().end()
                }
                recording.exported.peek().map { it.name } shouldBe cases.map { it.third }
                recording.exported.peek().filter { it.name == "DB query" }.forEach {
                    it.attributes.get(AttributeKey.stringKey("db.operation.name")) shouldBe null
                }
                recording.assertNoMarker()
            }
        }

        test("fallback ordering prefers valid stable operation then legacy operation then stable summary token") {
            PrivacyRecorder().use { recording ->
                listOf(
                    Triple("INSERT", "UPDATE", "INSERT"),
                    Triple("unknown", "UPDATE", "UPDATE"),
                    Triple("SELECT extra", "UPDATE extra", "DELETE"),
                ).forEach { (stable, legacy, expected) ->
                    recording.tracer.spanBuilder(PRIVATE_MARKER)
                        .setAttribute("db.operation.name", stable)
                        .setAttribute("db.operation", legacy)
                        .setAttribute("db.query.summary", "DELETE unapproved")
                        .startSpan().end()
                    recording.exported.peek().last().name shouldBe expected
                }
                recording.tracer.spanBuilder("SELECT bookings").startSpan().end()
                recording.exported.peek().last().name shouldBe "DB query"
                recording.assertNoMarker()
            }
        }

        test("no approved identifiers permits operation-only names and wrong attribute types are discarded") {
            PrivacyRecorder(emptySet()).use { recording ->
                recording.tracer.spanBuilder(PRIVATE_MARKER)
                    .setAllAttributes(
                        Attributes.builder()
                            .put("db.query.summary", "SELECT bookings")
                            .put("db.operation.name", 23L)
                            .put("db.system.name", 42L)
                            .put("db.operation.batch.size", "25")
                            .put("db.query.text", PRIVATE_MARKER)
                            .build(),
                    )
                    .startSpan().end()
                val span = recording.exported.peek().single()
                span.name shouldBe "SELECT"
                span.attributes.asMap() shouldBe Attributes.builder()
                    .put("db.query.summary", "SELECT")
                    .put("db.operation.name", "SELECT").build().asMap()
                recording.assertNoMarker()
            }
        }

        test("system names use only the approved vocabulary and only legacy aliases are translated") {
            val allowed = listOf(
                "postgresql", "mysql", "mariadb", "oracle.db",
                "microsoft.sql_server", "h2database", "sqlite", "other_sql",
            )
            val systemKey = AttributeKey.stringKey("db.system.name")
            PrivacyRecorder().use { recording ->
                allowed.forEach { system ->
                    recording.tracer.spanBuilder(PRIVATE_MARKER)
                        .setAttribute("db.system.name", system).startSpan().end()
                }
                recording.exported.peek().map { it.attributes.get(systemKey) } shouldBe allowed
                val aliases = listOf("h2" to "h2database", "mssql" to "microsoft.sql_server", "oracle" to "oracle.db")
                aliases.forEach { (legacy, expected) ->
                    recording.tracer.spanBuilder(PRIVATE_MARKER).setAttribute("db.system", legacy).startSpan().end()
                    recording.exported.peek().last().attributes.get(systemKey) shouldBe expected
                }
                listOf("h2", "POSTGRESQL", PRIVATE_MARKER).forEach { invalid ->
                    recording.tracer.spanBuilder(PRIVATE_MARKER)
                        .setAttribute("db.system.name", invalid)
                        .setAttribute("db.system", "postgresql").startSpan().end()
                    recording.exported.peek().last().attributes.get(systemKey) shouldBe null
                }
                recording.assertNoMarker()
            }
        }

        test("batch sizes retain only integer values within the inclusive limit") {
            PrivacyRecorder().use { recording ->
                listOf(-1L, 0L, 1_000_000L, 1_000_001L).forEach { count ->
                    recording.tracer.spanBuilder(PRIVATE_MARKER)
                        .setAttribute("db.operation.batch.size", count).startSpan().end()
                }
                val batchKey = AttributeKey.longKey("db.operation.batch.size")
                recording.exported.peek().map { it.attributes.get(batchKey) } shouldBe
                    listOf(null, 0L, 1_000_000L, null)
            }
        }
    }
}
