# Feature flags

`surefold-openfeature` reads [OpenFeature](https://openfeature.dev) flags on
virtual threads. Who a flag is evaluated for is bound once, where a request
comes in, and every flag read below it answers for that caller: in a `parZip`
branch, in a fork, and in an actor's handler. A flag is declared once, with its
key, its type and its default, and read with no client passed in.

```kotlin
dependencies {
    // brings surefold, the OpenFeature SDK and the slf4j-api it logs through
    implementation("io.github.matthewjones372:surefold-openfeature:0.17.0")
}
```

Call `OpenFeatureAPI.getInstance().useSurefold()` once, in `main`, before setting a
provider. It keeps the SDK's evaluation context where forks and messages can
see it, and adds the hook that counts evaluations.

In a `surefold-app` graph, `surefold-app-openfeature`'s `openFeature { provider }` does
this for you. The provider is a node keyed by `Client`, set before any node
that takes one is built. A provider whose init throws refuses the start, the
graph reports not ready while the provider is in error or stale, and release
shuts the SDK down. `Flag.value()` reads the same provider. One graph per JVM
can hold it, and a second is refused.

## Flags in the service's config

`surefold-app-openfeature-flagd`'s `flagdFlags("flags")` reads the flags from a
section of the service's own HOCON, in [flagd's schema](https://flagd.dev/reference/flag-definitions/),
and evaluates them in-process. HOCON is a superset of JSON, so a flagd
definition is already a valid section, targeting included:

```hocon
flags {
  "adoption.per-customer-limit" {
    state = ENABLED
    variants { standard = 3, raised = 5 }
    defaultVariant = standard
    targeting { "if" = [{ "==" = [{ "var" = "targetingKey" }, "ada"] }, "raised", "standard"] }
  }
}
```

The targeting key is the one `evaluatedFor` binds. A section flagd rejects
refuses the start, naming the flag and the file it came from, and a test
overrides the section like any other setting. A change is a redeploy. The
module brings flagd's gRPC client with it, so a service on another provider
never carries it.

## Flags per tenant from Postgres

`surefold-openfeature-postgres`'s `PostgresFlags` reads flags from a table, one
row per flag for every tenant and one per tenant that differs, so an operator
can pause checkout for one tenant tonight without a deploy. The read runs in
the tenant's own transaction, under the table's row-level security policy, so
a tenant never sees another tenant's rows.

```kotlin
dependencies {
    // brings surefold-openfeature, surefold-tenancy, surefold-cache and surefold-postgres-notify
    implementation("io.github.matthewjones372:surefold-openfeature-postgres:0.17.0")
}
```

The table ships as a changelog: `<include file="surefold/openfeature-postgres/postgres.sql"/>`
on Liquibase, or `flyway(SurefoldSchema.OPENFEATURE_POSTGRES, …)` on Flyway. It
holds `surefold_feature_flags (key, tenant_id, value, reason, updated_by, updated_at)`
with a null `tenant_id` for the row every tenant gets, forced row-level
security with this policy, and a trigger that notifies each change:

```sql
create policy surefold_feature_flags_read on surefold_feature_flags for select
    using (tenant_id is null or tenant_id = current_setting('app.tenant_id', true));
```

Grants are the service's: `select` to the application's role, and writes to
the role its admin endpoint uses, which needs `BYPASSRLS` to write any tenant's
row, since the table has no write policy.

```sql
grant select on surefold_feature_flags to app;
grant select, insert, update, delete on surefold_feature_flags to app_admin;
```

<!-- flags-postgres -->
```kotlin
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.providers.memory.InMemoryProvider
import io.github.matthewjones372.surefold.openfeature.Flag
import io.github.matthewjones372.surefold.openfeature.postgres.FLAGS_CHANNEL
import io.github.matthewjones372.surefold.openfeature.postgres.PostgresFlags
import io.github.matthewjones372.surefold.postgres.notify.PostgresNotifications
import io.github.matthewjones372.surefold.stream.Run
import io.github.matthewjones372.surefold.tenancy.TenantId
import io.github.matthewjones372.surefold.tenancy.withTenant
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

val CheckoutPaused = Flag.boolean("checkout.paused", default = false)

// [app] is the application's pool; [listener] a source of its own, since listening holds one connection.
fun flags(app: DataSource, listener: DataSource): Pair<PostgresFlags, Run<Nothing, Long>> {
    val provider = PostgresFlags(app, fallback = InMemoryProvider(emptyMap()), ttl = 30.seconds, stale = 10.minutes)
    OpenFeatureAPI.getInstance().setProviderAndWait(provider)
    // Start it beside the provider: another process's change, or one typed in psql, is seen within moments.
    val listening = provider.listening(PostgresNotifications.listen(listener, setOf(FLAGS_CHANNEL)))
    return provider to listening
}

// The tenant's row, else the row every tenant gets, else the fallback, else false.
fun paused(tenant: TenantId): Boolean = withTenant(tenant) { CheckoutPaused.value() }

fun pause(provider: PostgresFlags, admin: DataSource, tenant: TenantId, operator: String) =
    admin.connection.use { connection ->
        provider.set(connection, "checkout.paused", tenant, "true", reason = "fire alarm", by = operator)
    }
```

- **Whose rows**: the evaluation context's `tenant` attribute if set, so a
  tool can ask as any tenant, else the one `withTenant` bound. With neither,
  only the rows every tenant gets answer.
- **Types**: a row's text is read as the type asked for. One that does not
  parse answers the default with `PARSE_ERROR`, counted like any other
  fall-back. There are no object flags.
- **Cache**: a tenant's rows are read in one query and kept for `ttl`, however
  many evaluate at once. `set` and `clear` drop them at once in this process,
  and `listening` in every other, a row every tenant gets dropping them all.
- **Postgres down**: the last rows read answer for up to `stale`, and the
  provider reports `STALE`, so the graph reports not ready. Past that, or
  with nothing read yet, flags answer the fallback, and it reports `ERROR`.

## Declaring and reading a flag

<!-- flags-declare -->
```kotlin
import dev.openfeature.sdk.FlagEvaluationDetails
import dev.surefold.parZip
import io.github.matthewjones372.surefold.openfeature.Flag
import io.github.matthewjones372.surefold.openfeature.evaluatedFor

object AdoptionFlags {
    val registryCheck = Flag.boolean("adoption.registry-check", default = true)
    val perAdopterLimit = Flag.int("adoption.per-customer-limit", default = 3)
}

fun limits(customer: String, tier: String): Pair<Boolean, Int> =
    evaluatedFor(customer, "tier" to tier) {
        // Both branches answer for this customer: the context crosses the fork.
        parZip({ AdoptionFlags.registryCheck.value() }, { AdoptionFlags.perAdopterLimit.value() }) { check, limit ->
            check to limit
        }
    }

fun why(customer: String): FlagEvaluationDetails<Int> =
    evaluatedFor(customer) { AdoptionFlags.perAdopterLimit.details() }
```

- `Flag.boolean`, `Flag.string`, `Flag.int` and `Flag.double` each declare a
  flag. They are separate factories, so a default of `3` or `3.0` cannot pick
  the type silently.
- `value()` answers the flag, or its default when it cannot be evaluated: a
  flag never fails a request. `details()` adds the variant, the reason and,
  when it fell back, the error code.
- `evaluatedFor(targetingKey, attributes…) { }` binds who the flag is for. A
  nested call adds its attributes to the ones around it.
- A message sent to an actor carries the targeting key and every string,
  boolean, int, long and double attribute, each arriving as the type it was.
  Structures, lists and instants stay behind.

## Flags of other types

A timeout, a cut-over time, a mode or a small structure is declared as the
type the code wants, and decoded once, beside the key and the default:

<!-- flags-types -->
```kotlin
import dev.openfeature.sdk.Value
import io.github.matthewjones372.surefold.openfeature.Flag
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

enum class Checkout { Classic, Express }

data class Window(val from: Int, val to: Int)

object CheckoutFlags {
    val maxBasket = Flag.long("checkout.max-basket", default = 50L)
    val registryTimeout = Flag.duration("checkout.registry-timeout", default = 2.seconds)
    val newRulesFrom = Flag.instant("checkout.new-rules-from", default = Instant.MAX)
    val checkout = Flag.enum("checkout.mode", default = Checkout.Classic)
    val window = Flag.of("checkout.window", default = Window(0, 24)) { value: Value ->
        value.asStructure().let { Window(it.getValue("from").asInteger(), it.getValue("to").asInteger()) }
    }
}
```

| Factory | The provider holds | Decoded by |
|---|---|---|
| `Flag.long` | an integer | the SDK |
| `Flag.duration` | a string such as `"5s"`, `"1m 30s"` or `"PT5S"` | `Duration.parse` |
| `Flag.instant` | an ISO-8601 string | `Instant.parse` |
| `Flag.enum` | a string naming a constant exactly | `enumValueOf` |
| `Flag.of` | an object | the function you pass |

A value that will not decode answers the default, with `PARSE_ERROR` and the
reason in `details()`, and is warned about and counted like any other fall-back.

## Checking flags in the config at start

When the flags come from the service's own config, `flagdFlags` can check them
against their declarations before the first request does. Each flag object
lists what it declares, and the graph passes the lists in:

```kotlin
object AdoptionFlags {
    val registryCheck = Flag.boolean("adoption.registry-check", default = true)
    val perAdopterLimit = Flag.int("adoption.per-customer-limit", default = 3)
    val all = listOf(registryCheck, perAdopterLimit)
}

val flags: Module = flagdFlags("flags", AdoptionFlags.all + CheckoutFlags.all)
```

Every variant of each declared flag must read as the flag's type. A `Flag.enum`
variant has to name a constant, a `Flag.duration` variant has to parse, and a
`Flag.of` variant has to pass the decoder. A whole number too big for an `Int`
is refused for any numeric flag, `Flag.long` included, because flagd reads such
a number as none of its numeric types. A declared flag missing from the section
is refused too, since it would only ever answer its default. Every miss is
named in one refusal, each with the line it is written on:

```
flags at flags do not match their declarations:
  adoption.per-customer-limit: variant raised 5.5 is not a whole number (application.conf: 14)
  checkout.mode: variant new "Fast" did not decode as a Checkout: … (application.conf: 22)
  checkout.registry-timeout: declared but not in the section (application.conf: 9)
```

A key in the section that nothing declares is left alone: it may belong to code
not deployed yet. `Flag.rejects(value)` is the same check, one value at a time,
for a provider of your own.

## What gets counted

Every evaluation is counted under `openfeature.evaluations`, tagged `flag`,
`variant` and `reason`. One that fell back to its default also writes a
warning naming the flag and the error code, and is counted under
`openfeature.errors`, tagged `flag` and `code`. Both go to whatever logger and
metrics the service bound, the same as [Surefold's own](observability.md).

## Testing

`featureClient` is the client a flag is read through. A test binds one with a
provider of its own, and the forks it opens read the same one:

<!-- flags-test -->
```kotlin
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.providers.memory.InMemoryProvider
import io.github.matthewjones372.surefold.openfeature.Flag
import io.github.matthewjones372.surefold.openfeature.featureClient
import io.github.matthewjones372.surefold.openfeature.useSurefold
import dev.openfeature.sdk.providers.memory.Flag as MemoryFlag

val perAdopterLimit = Flag.int("adoption.per-customer-limit", default = 3)

fun aTest(): Int {
    val api = OpenFeatureAPI.getInstance().apply { useSurefold() }
    val five = MemoryFlag.builder<Int>().variant("on", 5).defaultVariant("on").build()
    api.setProviderAndWait("a-test", InMemoryProvider(mapOf("adoption.per-customer-limit" to five)))

    return featureClient.locally(api.getClient("a-test")) { perAdopterLimit.value() }
}
```
