package io.github.matthewjones372.surefold.app.liquibase

import dev.surefold.actor.journal.jdbc.Postgres
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.withClue
import io.github.matthewjones372.surefold.app.Migrated
import io.github.matthewjones372.surefold.app.render
import io.github.matthewjones372.surefold.app.report
import io.github.matthewjones372.surefold.app.single
import io.github.matthewjones372.surefold.app.testApp
import io.github.matthewjones372.surefold.app.validate
import org.postgresql.ds.PGSimpleDataSource
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

private const val CHANGELOG = "db/changelog.sql"

/** A count nobody could have taken before the changelog created the table. */
private class Orders(val rows: Int)

private fun database(): DataSource = Postgres.empty()

private const val OWNER = "schema_owner"

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

private fun counting(source: DataSource): Int =
    source.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("select count(*) from orders").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

object MigrationsSuite : Suite() {
    init {
        test("a changelog runs, and what it created is there to read") {
            val source = database()
            val app = single<DataSource> { source } +
                migrations(CHANGELOG) +
                single { db: DataSource, _: Migrated -> Orders(counting(db)) }

            testApp(app) { orders: Orders -> orders.rows } shouldBe 0
        }

        test("running it again applies nothing") {
            val source = database()
            val applied = AtomicInteger(-1)
            val app = single<DataSource> { source } +
                migrations(CHANGELOG) +
                single { done: Migrated -> applied.set(done.applied); Orders(0) }

            testApp(app) { _: Orders -> }
            val first = applied.get()
            testApp(app) { _: Orders -> }

            withClue("a changelog is a record of what has run, not a script that runs again") {
                first shouldBe 1
                applied.get() shouldBe 0
            }
        }

        test("the graph draws the reader after the migration") {
            val app = single<DataSource> { database() } +
                migrations(CHANGELOG) +
                single { _: Migrated -> Orders(0) }

            withClue("an ordering nobody can see is an ordering nobody keeps") {
                app.render() shouldContain "Migrated --> Orders"
            }
            app.validate().getOrNull().shouldNotBeNull()
        }

        test("a graph with no DataSource is refused before anything connects") {
            val app = migrations(CHANGELOG) + single { _: Migrated -> Orders(0) }

            app.validate().leftOrNull().shouldNotBeNull { errors -> errors.report() shouldContain "missing DataSource" }
        }

        test("the old name is surefold-app's Migrated, so a reader written against it still finds the node") {
            // The deprecated alias is the claim under test.
            @Suppress("DEPRECATION")
            val reader =
                single { done: io.github.matthewjones372.surefold.app.liquibase.Migrated -> Orders(done.applied) }
            val app = single<DataSource> { database() } + migrations(CHANGELOG) + reader

            testApp(app) { orders: Orders -> orders.rows } shouldBe 1
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
            val app = single { -> OwnerUrl(url) } +
                single<DataSource> { admin } +
                migrations(CHANGELOG) { owner: OwnerUrl -> OwnerPool(owner.url).also(opened::set) } +
                single { db: DataSource, _: Migrated ->
                    withClue("the owner's pool is the migration's, and gone before any reader is built") {
                        opened.get().closed.get() shouldBe true
                    }
                    Orders(counting(db))
                }

            testApp(app) { orders: Orders -> orders.rows } shouldBe 0
            val owner = admin.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("select tableowner from pg_tables where tablename = 'orders'").use { rows ->
                        rows.next()
                        rows.getString(1)
                    }
                }
            }
            owner shouldBe OWNER
        }
    }
}
