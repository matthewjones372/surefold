package dev.surefold.actor.journal.jdbc

import dev.surefold.actor.OffsetStore
import java.io.PrintWriter
import java.sql.Connection
import java.sql.DriverManager
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.system.exitProcess
import kotlin.time.Duration

private const val USAGE = """surefold-journal-move: moves a range of a sharded journal's slices between databases (spec 0105).

  move  --database NAME=JDBC_URL ... --slices FROM..TO --to NAME
  clean --database NAME=JDBC_URL ... --read-models A,B --after 1h

The first --database holds the slice table and the read models' offsets. The JDBC driver must be on the classpath;
user and password come from SUREFOLD_JOURNAL_USER and SUREFOLD_JOURNAL_PASSWORD when the URLs do not carry them."""

/** `surefold-journal-move`: [SliceMover] from a command line, for an operator with no admin endpoint of their own. */
fun main(args: Array<String>) {
    val command = args.firstOrNull()
    val options = args.drop(1).chunked(2).filter { it.size == 2 }.groupBy({ it[0] }, { it[1] })
    val databases = options["--database"].orEmpty().map { named ->
        val (name, url) = named.split("=", limit = 2).also { if (it.size != 2) usage() }
        name to Driven(url, System.getenv("SUREFOLD_JOURNAL_USER"), System.getenv("SUREFOLD_JOURNAL_PASSWORD"))
    }
    if (databases.isEmpty()) usage()
    val mover = SliceMover(databases)
    when (command) {
        "move" -> {
            val (from, to) = options["--slices"]?.single()?.split("..")?.map(String::toInt) ?: usage()
            println(mover.move(from..to, options["--to"]?.single() ?: usage()))
        }

        "clean" -> {
            val readModels = options["--read-models"]?.single()?.split(",") ?: usage()
            val after = options["--after"]?.single()?.let(Duration::parse) ?: usage()
            val offsets: OffsetStore = JdbcOffsets(databases.first().second)
            mover.cleanUp(offsets, readModels, after).forEach(::println)
        }

        else -> usage()
    }
}

private fun usage(): Nothing {
    System.err.println(USAGE)
    exitProcess(2)
}

/** A connection straight from [DriverManager] each time: enough for a command that runs one move. */
private class Driven(private val url: String, private val user: String?, private val password: String?) : DataSource {
    override fun getConnection(): Connection =
        if (user == null) DriverManager.getConnection(url) else DriverManager.getConnection(url, user, password)

    override fun getConnection(username: String?, password: String?): Connection =
        DriverManager.getConnection(url, username, password)

    override fun getLogWriter(): PrintWriter? = null

    override fun setLogWriter(out: PrintWriter?) = Unit

    override fun setLoginTimeout(seconds: Int) = DriverManager.setLoginTimeout(seconds)

    override fun getLoginTimeout(): Int = DriverManager.getLoginTimeout()

    override fun getParentLogger(): Logger = Logger.getLogger("surefold.journal.move")

    override fun <T : Any?> unwrap(iface: Class<T>): T = throw java.sql.SQLException("not a wrapper")

    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}
