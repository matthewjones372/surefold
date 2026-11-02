package io.github.matthewjones372.surefold.app

import dev.surefold.parMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/** What an application says about itself when asked. */
sealed class Health {

    data object Up : Health()

    /** Everything critical answered, and [failing] did not. */
    data class Degraded(val failing: List<String>) : Health()

    data class Down(val failing: List<String>) : Health()
}

/** The keys a running application provides itself, which no module has to. */
internal val runtimeProvided: Set<KType> = setOf(typeOf<HealthRegistry>())

/**
 * The probes of a running graph, asked again whenever something wants to know. A node takes one as a
 * dependency like any other, which is how a route answers `/ready` without being handed the graph.
 */
class HealthRegistry internal constructor(initial: List<Probe>) {

    // Grows when a choice's branch is built: its probes are only known then (spec 0136).
    private val probes = CopyOnWriteArrayList(initial)

    private val started = ConcurrentHashMap<KType, Any>()
    private val running = AtomicBoolean(false)

    internal fun started(key: KType, value: Any) {
        started[key] = value
    }

    internal fun adding(more: List<Probe>) {
        probes.addAll(more)
    }

    internal fun running() {
        running.set(true)
    }

    /** Up once every node has started; a node asking on the way up is told the graph is still coming up. */
    fun liveness(): Health = if (running.get()) Health.Up else Health.Down(listOf("starting"))

    /** Every probe asked at once, each inside its own timeout, so one wedged answer is not the answer. */
    fun readiness(): Health {
        val failing = parMap(probes) { probe -> if (answers(probe)) null else probe }.filterNotNull()
        val critical = failing.filter { it.critical }.map { it.name }
        val rest = failing.filterNot { it.critical }.map { it.name }
        return when {
            critical.isNotEmpty() -> Health.Down(critical + rest)
            rest.isNotEmpty() -> Health.Degraded(rest)
            else -> Health.Up
        }
    }

    private fun answers(probe: Probe): Boolean {
        val value = started[probe.key] ?: return false
        return probe.answered(value) == Answer.Yes
    }
}
