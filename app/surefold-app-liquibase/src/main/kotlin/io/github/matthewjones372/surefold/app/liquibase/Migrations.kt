package io.github.matthewjones372.surefold.app.liquibase

import dev.surefold.logInfo
import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.single
import liquibase.Contexts
import liquibase.LabelExpression
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import javax.sql.DataSource

/** surefold-app's `Migrated` under this module's name, for code written against it. */
@Deprecated(
    "Migrated is surefold-app's, so a reader does not depend on which tool migrated.",
    ReplaceWith("Migrated", "io.github.matthewjones372.surefold.app.Migrated"),
)
typealias Migrated = io.github.matthewjones372.surefold.app.Migrated

/**
 * Runs [changelog] against the [DataSource] the graph provides, once, before anything that takes a
 * [io.github.matthewjones372.surefold.app.Migrated] is built.
 *
 * The connection is the application's, and is given back before the node answers: a migration that
 * held one for the life of the process would be a pool slot nothing ever uses again.
 */
fun migrations(
    changelog: String,
    contexts: Contexts = Contexts(),
    labels: LabelExpression = LabelExpression(),
): Module = single { source: DataSource ->
    io.github.matthewjones372.surefold.app.Migrated(migrate(source, changelog, contexts, labels))
}

/**
 * [migrations] on a source [connect] opens from [S], a node in the graph, such as a pool logged in as the schema's
 * owner. It is closed, if it is `AutoCloseable`, before the node answers: the application never holds it.
 */
inline fun <reified S : Any> migrations(
    changelog: String,
    contexts: Contexts = Contexts(),
    labels: LabelExpression = LabelExpression(),
    noinline connect: (S) -> DataSource,
): Module = single { key: S ->
    val applied = connected(connect(key)) { source -> migrate(source, changelog, contexts, labels) }
    io.github.matthewjones372.surefold.app.Migrated(applied)
}

/** [use] on [source], closing it afterwards if it can be closed. */
@PublishedApi
internal fun <T> connected(source: DataSource, use: (DataSource) -> T): T =
    try {
        use(source)
    } finally {
        (source as? AutoCloseable)?.close()
    }

/**
 * Runs [changelog], a classpath resource, against [source] now, and answers how many changesets it applied. For a
 * database outside a graph; [migrations] is this as a node.
 */
fun migrate(
    source: DataSource,
    changelog: String,
    contexts: Contexts = Contexts(),
    labels: LabelExpression = LabelExpression(),
): Int = source.connection.use { connection ->
    val database = DatabaseFactory.getInstance()
        .findCorrectDatabaseImplementation(JdbcConnection(connection))
    Liquibase(changelog, ClassLoaderResourceAccessor(), database).use { liquibase ->
        val unrun = liquibase.listUnrunChangeSets(contexts, labels).size
        liquibase.update(contexts, labels)
        logInfo("liquibase: $changelog, $unrun changeset(s) applied")
        unrun
    }
}
