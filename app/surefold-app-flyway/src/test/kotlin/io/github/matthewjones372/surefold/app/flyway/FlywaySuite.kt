package io.github.matthewjones372.surefold.app.flyway

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withClue
import io.github.matthewjones372.surefold.actor.journal.jdbc.Postgres
import io.github.matthewjones372.surefold.app.Migrated
import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.render
import io.github.matthewjones372.surefold.app.single
import io.github.matthewjones372.surefold.app.testApp
import io.github.matthewjones372.surefold.app.use
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.configuration.Configuration
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension
import org.postgresql.ds.PGSimpleDataSource
import java.sql.ResultSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

private const val SERVICE = "classpath:db/migration"

/** A count nobody could have taken before the scripts created the table. */
private class Orders(val rows: Int)

/** Where the schema's owner logs in: a node the migration connects from. */
private class OwnerUrl(val url: String)

/** A source logged in as the schema's owner, as a service's owner pool is, that says whether it was closed. */
private class OwnerPool(url: String, private val inner: PGSimpleDataSource = PGSimpleDataSource()) :
    DataSource by inner,
    AutoCloseable {
    val closed = AtomicBoolean()

    init {
        inner.setUrl(url)
        inner.user = OWNER
        inner.password = OWNER
    }

    override fun close() = closed.set(true)
}

private const val OWNER = "schema_owner"

private fun <T> DataSource.query(sql: String, read: (ResultSet) -> T): T =
    connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                rows.next()
                read(rows)
            }
        }
    }

private fun DataSource.execute(sql: String) {
    connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
}

private fun counting(source: DataSource): Int = source.query("select count(*) from orders") { it.getInt(1) }

object FlywaySuite : Suite() {
    init {
        test("the scripts run first, and a reader of Migrated finds what they created") {
            val source = Postgres.empty()
            val app = single<DataSource> { source } +
                flyway(service = SERVICE) +
                single { db: DataSource, _: Migrated -> Orders(counting(db)) }

            testApp(app) { orders: Orders -> orders.rows } shouldBe 0
            withClue("an ordering nobody can see is an ordering nobody keeps") {
                app.render() shouldContain "Migrated --> Orders"
            }
        }

        test("a second run applies nothing") {
            val source = Postgres.empty()
            val applied = AtomicInteger(-1)
            val app = single<DataSource> { source } +
                flyway(service = SERVICE) +
                single { done: Migrated -> applied.set(done.applied); Orders(0) }

            testApp(app) { _: Orders -> }
            val first = applied.get()
            testApp(app) { _: Orders -> }

            first shouldBe 1
            applied.get() shouldBe 0
        }

        test("an applied script edited since fails the start, naming its checksum") {
            val source = Postgres.empty()
            migrate(source, service = SERVICE) shouldBe 1
            val app = single<DataSource> { source } +
                flyway(service = "classpath:db/edited") +
                single { _: Migrated -> Orders(0) }

            app.use { _: Orders -> }.leftOrNull().shouldNotBeNull { refused ->
                refused.toString() shouldContain "checksum mismatch for migration version 1"
            }
        }

        test("a first script that meets a table already there fails, rather than being skipped") {
            val source = Postgres.empty()
            source.execute("create table orders (id integer primary key)")

            shouldThrow<FlywayException>({ migrate(source, service = SERVICE) }) { refused ->
                refused.message shouldContain "already exists"
            }
        }

        test("a database with Liquibase's history is refused, naming the setting that hands it over") {
            val source = Postgres.empty()
            source.execute("create table databasechangelog (id text)")
            val app = single<DataSource> { source } + flyway(service = SERVICE) + single { _: Migrated -> Orders(0) }

            app.use { _: Orders -> }.leftOrNull().shouldNotBeNull { refused ->
                refused.toString() shouldContain "fromLiquibase"
            }
            source.hasTable("flyway_schema_history") shouldBe false
        }

        test("clean is disabled, so nothing can drop the schema") {
            val source = Postgres.empty()
            migrate(source, service = SERVICE)

            shouldThrow<FlywayException>({ configured(source, SERVICE, FlywaySettings()).clean() }) { refused ->
                refused.message shouldContain "disabled"
            }
            counting(source) shouldBe 0
        }

        test("the settings a service may change reach Flyway, and default as spec 0242 pins them") {
            val defaults = configured(Postgres.empty(), SERVICE, FlywaySettings()).configuration
            val changed = configured(Postgres.empty(), SERVICE, FlywaySettings(true, true, 3)).configuration
            val lock = { configuration: Configuration ->
                configuration.pluginRegister
                    .getExact(PostgreSQLConfigurationExtension::class.java)
                    .isTransactionalLock
            }

            listOf(defaults.isMixed, lock(defaults), defaults.connectRetries) shouldBe listOf(false, false, 0)
            listOf(changed.isMixed, lock(changed), changed.connectRetries) shouldBe listOf(true, true, 3)
            listOf(
                defaults.isCleanDisabled,
                defaults.isValidateOnMigrate,
                defaults.isBaselineOnMigrate,
                defaults.isOutOfOrder,
                defaults.isPlaceholderReplacement,
            ) shouldBe listOf(true, true, false, false, false)
        }

        test("with connect, the schema's owner owns the tables, and its pool is closed before Migrated is answered") {
            val url = Postgres.emptyUrl()
            val admin = PGSimpleDataSource().apply { setUrl(url) }
            admin.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("create role $OWNER login password '$OWNER'")
                    statement.execute("grant create on schema public to $OWNER")
                }
            }
            val opened = AtomicReference<OwnerPool>()
            val app: Module = single { -> OwnerUrl(url) } +
                single<DataSource> { admin } +
                flyway(service = SERVICE) { owner: OwnerUrl -> OwnerPool(owner.url).also(opened::set) } +
                single { db: DataSource, _: Migrated ->
                    withClue("the owner's pool is the migration's, and gone before any reader is built") {
                        opened.get().closed.get() shouldBe true
                    }
                    Orders(counting(db))
                }

            testApp(app) { orders: Orders -> orders.rows } shouldBe 0
            admin.query("select tableowner from pg_tables where tablename = 'orders'") { it.getString(1) } shouldBe
                OWNER
        }
    }
}
