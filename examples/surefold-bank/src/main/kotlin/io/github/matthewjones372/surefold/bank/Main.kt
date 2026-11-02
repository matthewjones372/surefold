package io.github.matthewjones372.surefold.bank

import dev.surefold.Promise
import dev.surefold.actor.InMemoryJournal
import dev.surefold.actor.journal.jdbc.JdbcJournal
import dev.surefold.actor.remote.Node
import dev.surefold.awaitValue
import dev.surefold.logInfo
import io.github.matthewjones372.surefold.cluster.Discovery
import kotlin.time.Duration.Companion.minutes

/** The three nodes: each one's name, the port its cluster listens on, and the port it serves HTTP on. */
internal val nodes = listOf(Triple("n1", 25521, 8081), Triple("n2", 25522, 8082), Triple("n3", 25523, 8083))

/**
 * `surefold-bank` runs the three nodes in this JVM, with the journal in memory. `--jdbc URL` keeps the journal in the
 * Postgres database at URL instead, and `--node n2` runs only that node, for a process of its own: separate
 * processes share one journal, so it needs `--jdbc`. The nodes run until the JVM is stopped.
 *
 * `--train` is the run an AOT cache is made from instead (spec 0278): it starts the bank, moves money through it,
 * and returns once everything is closed.
 */
fun main(args: Array<String>) {
    if (args.toList() == listOf("--train")) return train(TRAINING_TRANSFERS)
    require(args.size % 2 == 0) { "usage: surefold-bank [--node n1|n2|n3] [--jdbc URL]" }
    val options = args.toList().chunked(2).associate { it[0] to it[1] }
    val jdbc = options["--jdbc"]
    val only = options["--node"]
    require(only == null || jdbc != null) { "--node needs --jdbc: separate processes must share one journal" }
    val pool = jdbc?.let(::database)
    val journal = pool?.let(::JdbcJournal) ?: InMemoryJournal()
    val seeds = nodes.map { (_, port) -> Node("", "127.0.0.1", port) }.let { Discovery { it } }
    val chosen = nodes.filter { (name) -> only == null || name == only }
    val started = Promise<Nothing, List<Pair<BankNode, Api>>>("bank.nodes")
    // The admin's crash button: the node and its server go as a crash does, on a thread of their own.
    val crash = { name: String ->
        val found = started.awaitValue().firstOrNull { (node) -> node.name == name }
        found?.let { (node, api) ->
            Thread.ofVirtual().start {
                api.close()
                node.close()
            }
        }
        found != null
    }
    val running = chosen.map { (name, port, http) ->
        BankNode(name, port, seeds, journal).let { it to Api(it, http, crash = crash) }
    }
    started.succeed(running)
    logStarted()
    Runtime.getRuntime().addShutdownHook(
        Thread {
            running.forEach { (node, api) ->
                api.close()
                node.close()
            }
            pool?.close()
        },
    )
    running.forEach { (node, api) ->
        node.members.await(1.minutes) { seen -> seen.up.any { it.node.name == node.name } }
        logInfo("${node.name} is up: http://localhost:${api.port}")
    }
    logReady()
}
