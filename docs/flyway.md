# Migrations on Flyway

`surefold-app-flyway` runs SQL migrations on Flyway Community as a node. Anything that reads the database takes a
`Migrated`, so it starts after the scripts have run, whichever tool ran them (spec 0242).

```kotlin
dependencies {
    implementation("io.github.matthewjones372:surefold-app-flyway:0.17.0")
}
```

## Modules, then the service

<!-- flyway -->
```kotlin
import dev.surefold.app.Migrated
import dev.surefold.app.Module
import dev.surefold.app.single
import javax.sql.DataSource

class OrderRepo(val source: DataSource)

/** The jobs tables, then the service's own scripts, before anything reads the database. */
val database: Module =
    flyway(SurefoldSchema.JOBS, service = "classpath:db/handover", fromLiquibase = "shop:0014-orders") +
        single { db: DataSource, _: Migrated -> OrderRepo(db) }
```

- **The data modules** ship their tables as Flyway scripts, `surefold/<name>/flyway/V<n>__<changeset>.sql`, one per
  changeset of their Liquibase changelog. Each keeps its own history table, `flyway_surefold_<name>`, so a module's
  versions never meet the service's: a service on `V40` takes jobs' next script without `outOfOrder`.
- **The order** is `SurefoldSchema`'s, whatever the arguments' order: tenancy, jobs, idempotency, ratelimit, auth,
  openfeature-postgres, then the service's scripts at `service`, with Flyway's own `flyway_schema_history`.
- **Outside a graph**, `migrate(source, SurefoldSchema.JOBS, service = "classpath:db/migration")` runs the same now and
  answers how many scripts it applied.
- **As the schema's owner**, pass `connect = { s: DbSettings -> ownerPool(s) }`: the scripts run on that source, which
  is closed before `Migrated` is answered. The application keeps its own, less privileged pool.

## Settings

Pinned: `clean` is disabled; an applied script edited since fails the start with its checksum; no `baselineOnMigrate`,
no `outOfOrder`, and no placeholders, so a `${` in a function body is SQL. `FlywaySettings` holds what a service may
change: `mixed` (off: a script that needs `create index concurrently` says `executeInTransaction=false` in its own
`.sql.conf`), `transactionalLock` (off) and `connectRetries` (0: a service that cannot reach Postgres fails to start).

A history table with nothing in it starts at a baseline of `0`, so no script is skipped: a first script that meets a
table already there fails with `relation already exists`.

## From Liquibase

A database that has Liquibase's `databasechangelog` is refused unless `fromLiquibase` is set. Over three releases:

1. **Write `V1`**, the service's schema today, as a schema-only dump without the modules' or Liquibase's tables:

   ```bash
   pg_dump --schema-only --no-owner --no-privileges \
     --exclude-table='surefold_*' --exclude-table='databasechangelog*' "$DATABASE_URL" > V1__schema.sql
   ```

   Prove it with a test: a database migrated by the changelog and one migrated by Flyway alone hold the same tables,
   columns, constraints, indexes, policies, grants and functions. `SurefoldSchemaSuite` in `surefold-app-flyway` is
   the shape to copy.
2. **Release on Flyway** with `fromLiquibase` naming the last changeset the changelog applies, `author:id`, as above.
   On a database with no Flyway history whose last Liquibase row is that changeset, the service is baselined at `V1`
   and each module at the script of its last changeset Liquibase applied. Any other Liquibase history refuses the
   start, naming the changeset it found; a new database has none, and runs `V1`. Liquibase's tables stay, so the
   release before can still be rolled back to.
3. **Release `V2__drop_liquibase.sql`**, keeping `fromLiquibase` until every database has run it:

   ```sql
   drop table if exists databasechangelog;
   drop table if exists databasechangeloglock;
   ```

   Then drop `fromLiquibase`, and `surefold-app-liquibase` with it.
