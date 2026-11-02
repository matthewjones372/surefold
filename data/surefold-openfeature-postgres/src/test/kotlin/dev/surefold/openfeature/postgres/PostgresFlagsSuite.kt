package dev.surefold.openfeature.postgres

import dev.openfeature.sdk.ErrorCode
import dev.openfeature.sdk.EvaluationContext
import dev.openfeature.sdk.ImmutableContext
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.ProviderState
import dev.openfeature.sdk.Reason
import dev.openfeature.sdk.Value
import dev.openfeature.sdk.exceptions.FlagNotFoundError
import dev.openfeature.sdk.providers.memory.Flag
import dev.openfeature.sdk.providers.memory.InMemoryProvider
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import dev.surefold.tenancy.RlsDatabase
import dev.surefold.tenancy.TenantId
import dev.surefold.tenancy.withTenant
import org.postgresql.ds.PGSimpleDataSource

/** Whose row a flag answers, on Postgres under the table's own policy (spec 0240). */
object PostgresFlagsSuite : Suite() {
    private val acme = TenantId("acme")
    private val globex = TenantId("globex")
    private val nobody = ImmutableContext()

    private fun database(): RlsDatabase = RlsDatabase(bookings = false).also { database ->
        database.apply("surefold/openfeature-postgres/postgres.sql")
        database.execute("grant select on surefold_feature_flags to surefold_app")
    }

    private fun RlsDatabase.row(key: String, tenant: String?, value: String) = execute(
        "insert into surefold_feature_flags values " +
            "('$key', ${tenant?.let { "'$it'" } ?: "null"}, '$value', 'a test', 'ops:test', now())",
    )

    private fun fallback() = InMemoryProvider(
        mapOf(
            "checkout.paused" to Flag.builder<Boolean>()
                .variants(mapOf("on" to true))
                .defaultVariant("on")
                .build(),
        ),
    )

    init {
        test("a flag answers acme's row for acme, the null row for globex, then the fallback, then the default") {
            val database = database()
            database.row("checkout.limit", "acme", "5")
            database.row("checkout.limit", null, "10")
            val flags = PostgresFlags(database.app, fallback()).also { it.initialize(nobody) }

            val forAcme = withTenant(acme) { flags.getIntegerEvaluation("checkout.limit", 0, nobody) }
            forAcme.value shouldBe 5
            forAcme.reason shouldBe Reason.TARGETING_MATCH.name
            val forGlobex = withTenant(globex) { flags.getIntegerEvaluation("checkout.limit", 0, nobody) }
            forGlobex.value shouldBe 10
            forGlobex.reason shouldBe Reason.STATIC.name
            withTenant(acme) { flags.getBooleanEvaluation("checkout.paused", false, nobody) }.value shouldBe true
            // A flag the fallback lacks is its FlagNotFoundError, as alone, which the SDK answers with the default.
            shouldThrow<FlagNotFoundError> { withTenant(acme) { flags.getStringEvaluation("theme", "plain", nobody) } }
            val alone = PostgresFlags(database.app)
            withTenant(acme) { alone.getStringEvaluation("theme", "plain", nobody) }.value shouldBe "plain"
        }

        test("an object flag is the fallback's, since a row holds no object") {
            val database = database()
            database.row("layout", null, "{}")
            val flags = PostgresFlags(database.app)

            val read = flags.getObjectEvaluation("layout", Value("grid"), nobody)
            read.value shouldBe Value("grid")
            read.reason shouldBe Reason.DEFAULT.name
        }

        test("the fallback is started and stopped with the provider, since the SDK starts only the one it is given") {
            val fallen = Recording()
            val flags = PostgresFlags(PGSimpleDataSource(), fallen)

            flags.initialize(nobody)
            flags.shutdown()
            fallen.calls shouldBe listOf("initialize", "shutdown")
        }

        test("the SDK starts it, as it does every provider, with no evaluation context") {
            val api = OpenFeatureAPI.getInstance()
            api.setProviderAndWait("postgres-flags-start", PostgresFlags(PGSimpleDataSource()))
            api.getClient("postgres-flags-start").providerState shouldBe ProviderState.READY
            api.shutdown()
        }

        test("a row of another tenant is never read") {
            val database = database()
            database.row("pilot", "acme", "true")
            val flags = PostgresFlags(database.app)

            val forGlobex = withTenant(globex) { flags.getBooleanEvaluation("pilot", false, nobody) }
            forGlobex.value shouldBe false
            forGlobex.reason shouldBe Reason.DEFAULT.name
        }

        test("the context's tenant attribute is read before the bound tenant") {
            val database = database()
            database.row("pilot", "acme", "true")
            val flags = PostgresFlags(database.app)
            val asAcme = ImmutableContext(mapOf(TENANT_ATTRIBUTE to Value("acme")))

            withTenant(globex) { flags.getBooleanEvaluation("pilot", false, asAcme) }.value shouldBe true
        }

        test("with no tenant, only the null rows answer") {
            val database = database()
            database.row("limit", "acme", "5")
            database.row("limit", null, "10")
            database.row("acme.only", "acme", "on")
            val flags = PostgresFlags(database.app)

            flags.getIntegerEvaluation("limit", 0, nobody).value shouldBe 10
            flags.getStringEvaluation("acme.only", "off", nobody).value shouldBe "off"
        }

        test("a row that does not parse as the type asked for answers the default with PARSE_ERROR") {
            val database = database()
            database.row("limit", null, "many")
            val flags = PostgresFlags(database.app)

            val read = flags.getIntegerEvaluation("limit", 3, nobody)
            read.value shouldBe 3
            read.errorCode shouldBe ErrorCode.PARSE_ERROR
            flags.getDoubleEvaluation("limit", 0.5, nobody).errorCode shouldBe ErrorCode.PARSE_ERROR
        }
    }
}

/** A fallback that says when it was started and stopped. */
private class Recording : InMemoryProvider(emptyMap()) {
    val calls = mutableListOf<String>()

    override fun initialize(evaluationContext: EvaluationContext) {
        calls += "initialize"
        super.initialize(evaluationContext)
    }

    override fun shutdown() {
        calls += "shutdown"
        super.shutdown()
    }
}
