# Tenancy

`surefold-tenancy` makes Postgres enforce whose rows a transaction sees. Every tenant's rows live in shared tables
under a row-level security policy, every transaction says whose it is, and a transaction that says nothing is refused
before it reaches the database. Work that must cross tenants takes a separate, audited path.

```kotlin
dependencies {
    implementation("dev.surefold:surefold-tenancy:0.18.0")
    testImplementation("dev.surefold:surefold-tenancy-test:0.18.0")
}
```

## A tenant, carried

```kotlin
withTenant(TenantId("acme")) {
    currentTenant()                              // TenantId(acme), here and in every fork opened here
    Jobs.enqueue(connection, SendReminder, …)    // the tenant rides the job, and is bound again when it runs
}
```

The tenant is a `SurefoldLocal`, so a `parMap`, a stream on the forks backend and any other fork see it. A registered
`Carrier` carries it across a `tell` to an actor and into an enqueued job, beside the trace.

## A policy per table

The policies are the service's, in its own changelog. Every table with tenants' rows gets the same three statements:

<!-- tenancy-changelog -->
```sql
--liquibase formatted sql

--changeset example:bookings
create table bookings (
    id        bigint generated always as identity primary key,
    -- Filled from the transaction's tenant, so an insert cannot name another.
    tenant_id text   not null default current_setting('app.tenant_id', true),
    name      text   not null
);
create index bookings_tenant on bookings (tenant_id);

alter table bookings enable row level security;
-- Without this, the table's owner, which a migration often runs as, would see every tenant's rows.
alter table bookings force row level security;

create policy tenant_isolation on bookings
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));
```

- `force row level security` matters: without it, the table's owner sees every row, and a migration, or a service,
  often connects as the owner.
- `current_setting('app.tenant_id', true)` answers null when nothing set it, and a null matches no row, so a
  connection that skipped `TenantTransactions` sees nothing rather than everything.
- The application's role must be neither a superuser nor `BYPASSRLS`. Its connections are the ones the policy binds.

Include surefold's own changelog for the audit table too: `<include file="surefold/tenancy/postgres.sql"/>`. On Flyway,
`flyway(SurefoldSchema.TENANCY, …)` runs the same SQL ([Flyway](flyway.md)).

## Transactions

```kotlin
val db = TenantTransactions(app = appDataSource, system = systemDataSource)

db.transaction { connection -> … }               // as the bound tenant, or Left(NoTenant) with none bound
db.transaction(TenantId("acme")) { connection -> … }
db.system(actor = "jobs-worker", reason = "claim every tenant's jobs") { connection -> … }
```

`transaction` runs `select set_config('app.tenant_id', ?, true)` in the transaction it opens, which lasts until that
transaction ends as `SET LOCAL` does, so a pooled connection carries no tenant to its next user. It commits when the
block returns and rolls back when it throws.

`system` is the one way across tenants. It runs on a second `DataSource` whose role has `BYPASSRLS`, so no SQL the
application runs on its own connections can switch the policies off. Each call writes who ran it and why to
`surefold_tenancy_audit`, in the same transaction, and logs a line.

```sql
create role booking_app login password '…';                     -- the application's role: the policies bind it
create role booking_system login password '…' bypassrls;        -- the system path's role
grant insert on surefold_tenancy_audit to booking_system;
```

## Who is acting, and as what

A tenant is often not enough: a policy may also narrow by the member's role or the acting user. Declare each such
setting once, with the value it takes when none is bound, and bind values beside the tenant:

```kotlin
val db = TenantTransactions(app = appDataSource, settings = listOf(Setting("app.member_role", absent = "none"),
                                                                   Setting("app.user_id", absent = "")))
withTenant(TenantId("acme"), "app.member_role" to "manager", "app.user_id" to user.id) {
    db.transaction { connection -> … }
}
```

```sql
create policy managers_only on rotas as restrictive
    using (current_setting('app.member_role') = 'manager');
```

Every tenant transaction sets each declared setting, transaction-locally, to its bound value or to `absent`, so a
restrictive policy sees `none`, never a pooled connection's last value, and fails closed. Names are `word.word`. Forks
see the values, as they see the tenant; a nested `withTenant` that binds none clears them; and they do not ride a
`tell` or a job, which run as the tenant with every setting `absent`.

## A system path with no bypass

`system` runs on a role with `BYPASSRLS`: one careless query on it reads every tenant's every row. A service can
instead cross tenants on a role that holds no table grants at all and may only `EXECUTE` a pinned list of
`SECURITY DEFINER` functions, each answering only what its purpose needs. Whatever SQL then runs on that role, Postgres
lets it read no row.

```kotlin
val sys = SystemFunctions(systemDataSource, schema = "sys")
val count = sys.call(actor = "api", reason = "count a tenant's bookings") { calls ->
    calls.one("booking_count", tenant.value) { row -> row.getLong(1) }
}
```

`call` opens one transaction and hands its block `FunctionCalls`, never the `Connection`: `one`, `optional` and `query`
run `select * from sys.<function>(?, …)` with bound arguments, and names are checked as identifiers. Every call logs
its actor and reason. `audited = true` also writes the `surefold_tenancy_audit` row, through
`surefold_tenancy_audit_write`, a definer function in surefold's changelog: grant the role `EXECUTE` on it, not the
table. Leave it off for a lookup on every request, and turn it on for writes and operator work.

The functions are the service's, in its changelog. A `NOLOGIN` role owns them and holds only the grants they use. They
cannot be owned by the table's owner: under `force row level security` its policies bind the owner too, so such a
function would read nothing. Pin each function's `search_path`, and revoke `EXECUTE` from `public`, which every
function grants by default:

<!-- system-functions-changelog -->
```sql
--liquibase formatted sql

--changeset example:system-functions
-- Owns the functions and logs in as no one. It holds only what they read, and a policy lets it read across tenants.
create role booking_definer nologin;
grant select on bookings to booking_definer;
create policy booking_definer_reads on bookings for select to booking_definer using (true);

-- The role SystemFunctions runs on: no table grants, only EXECUTE on the functions below.
create role booking_system login password 'change-me';
create schema sys authorization booking_definer;
grant usage on schema sys to booking_system;

create function sys.booking_count(tenant text) returns bigint
    language sql stable security definer set search_path = public, pg_temp
    as $$ select count(*) from bookings where tenant_id = tenant $$;
alter function sys.booking_count(text) owner to booking_definer;
revoke execute on function sys.booking_count(text) from public;
grant execute on function sys.booking_count(text) to booking_system;

grant execute on function surefold_tenancy_audit_write(text, text) to booking_system;
```

`checkSystemFunctions` from `surefold-tenancy-test` holds the role to that list. Run it in a test against a database
migrated as production is, reading the catalog as a superuser:

```kotlin
checkSystemFunctions(superuserDataSource, role = "booking_system",
                     expected = setOf("sys.booking_count", "public.surefold_tenancy_audit_write"))
```

It fails, one line per problem, when the role may execute a definer function not in `expected` or cannot execute one
that is, when one of them has no pinned `search_path`, when the role holds any privilege on a table, view or sequence
or may create in a schema or the database, and when it is a superuser or has `BYPASSRLS`. A new cross-tenant need is a
new function, a migration and a change to `expected`, which review sees.

## Jobs

With `surefold-jobs-tenancy` on the classpath, each job's handler runs as the tenant in its row's `tenant_id`,
whoever enqueued it, so the handler's `TenantTransactions.transaction` sees that tenant's rows only. A job with no
tenant runs with none, and its tenant transactions are refused.

The jobs tables themselves hold every tenant's jobs and carry no policy: give the worker the system `DataSource`, so
its claims cross tenants on the role that is meant to.

## Testing isolation

`checkIsolated` from `surefold-tenancy-test` writes rows as two tenants and reads as each, and fails if either sees a
row of the other's, or cannot see its own, which would mean the check proved nothing:

```kotlin
checkIsolated(
    TenantTransactions(appDataSource),
    insert = { connection, tenant -> connection.prepareStatement("insert into bookings (name) values ('x')").use { it.executeUpdate() } },
    read = { connection -> connection.createStatement().use { s -> s.executeQuery("select tenant_id from bookings").use { rows -> generateSequence { if (rows.next()) TenantId(rows.getString(1)) else null }.toList() } } },
)
```

Run it once per table with tenants' rows, against a database migrated as production is, connecting as the
application's role.
