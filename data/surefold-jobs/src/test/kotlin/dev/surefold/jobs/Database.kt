package dev.surefold.jobs

import dev.surefold.actor.journal.jdbc.Postgres
import dev.surefold.actor.journal.jdbc.migrate
import java.sql.Connection
import javax.sql.DataSource

/** A fresh database with the jobs changelog applied, as a service's own changelog would include it. */
fun jobsDatabase(): DataSource = Postgres.empty().also { source ->
    source.migrate("surefold/jobs/postgres.sql")
}

/** Runs [block] in one transaction, committed when it returns. */
fun <A> DataSource.committed(block: (Connection) -> A): A = connection.use { connection ->
    connection.autoCommit = false
    block(connection).also { connection.commit() }
}

/** Each row of [sql]'s first column. */
fun DataSource.column(sql: String): List<Any?> = connection.use { connection ->
    connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            generateSequence { if (rows.next()) rows.getObject(1) else null }.toList()
        }
    }
}

object Notes : JobCodec<String> {
    override val version = 2

    override fun encode(payload: String) = "{\"note\": \"$payload\"}"

    override fun decode(version: Int, json: String) = json.substringAfter(": \"").substringBefore('"')
}

object Remind : JobKind<String>("remind", Notes)
