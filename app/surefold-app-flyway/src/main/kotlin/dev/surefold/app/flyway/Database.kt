package dev.surefold.app.flyway

import javax.sql.DataSource

/** Whether [table] exists on the connection's search path. */
internal fun DataSource.hasTable(table: String): Boolean =
    strings("select to_regclass('$table')::text").isNotEmpty()

/** The first column of every row [sql] answers. */
internal fun DataSource.strings(sql: String): List<String> =
    connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
            }
        }
    }
