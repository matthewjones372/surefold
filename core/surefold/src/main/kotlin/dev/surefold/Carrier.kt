package dev.surefold

import java.util.ServiceConfigurationError
import java.util.ServiceLoader

/**
 * What rides a message from the thread that sent it to the one that handles it (spec 0122): a fork's locals cross a
 * fork, and a carrier is how the ones that matter cross a `tell` too.
 *
 * Registered through `META-INF/services`, as the logger is, so a module on the classpath carries its own: `surefold`
 * carries `logAnnotated`'s annotations, `surefold-otel` the trace. A map of strings, because that is what crosses a
 * node as it is, and what every propagator already reads and writes.
 */
interface Carrier {
    /** What to carry from the sending thread, empty when there is nothing. */
    fun capture(): Map<String, String>

    /** Runs [block] with [carried] bound as the sender had it. Keys that are not this carrier's are left alone. */
    fun <A> within(carried: Map<String, String>, block: () -> A): A
}

/** Every registered [Carrier], as one. */
object Carriers {
    private val registered: List<Carrier> by lazy {
        try {
            ServiceLoader.load(Carrier::class.java, Carrier::class.java.classLoader).toList()
        } catch (failed: ServiceConfigurationError) {
            System.err.println("surefold: a registered Carrier could not be loaded, so nothing rides messages: $failed")
            emptyList()
        } catch (failed: LinkageError) {
            System.err.println("surefold: a registered Carrier could not be loaded, so nothing rides messages: $failed")
            emptyList()
        }
    }

    /** Every carrier's capture, as one map; empty, and allocating nothing, when none has anything to carry. */
    fun capture(): Map<String, String> {
        var all: Map<String, String> = emptyMap()
        for (carrier in registered) {
            val one = carrier.capture()
            if (one.isNotEmpty()) all = if (all.isEmpty()) one else all + one
        }
        return all
    }

    /** Runs [block] inside every carrier's [Carrier.within], the first registered outermost. */
    fun <A> within(carried: Map<String, String>, block: () -> A): A = nested(carried, 0, block)

    private fun <A> nested(carried: Map<String, String>, from: Int, block: () -> A): A =
        if (from == registered.size) block() else registered[from].within(carried) { nested(carried, from + 1, block) }
}

/** `logAnnotated`'s annotations, under a prefix of their own so a trace's keys never meet them. */
internal class AnnotationCarrier : Carrier {
    override fun capture(): Map<String, String> {
        val held = boundAnnotations()
        return if (held.isEmpty()) emptyMap() else held.mapKeys { (key, _) -> PREFIX + key }
    }

    override fun <A> within(carried: Map<String, String>, block: () -> A): A {
        val sent = carried.filterKeys { it.startsWith(PREFIX) }.mapKeys { (key, _) -> key.removePrefix(PREFIX) }
        // What was sent replaces what was bound here rather than adding to it, so the link has nothing outside it.
        return if (sent.isEmpty()) block() else annotations.locally(Annotated(emptyArray(), null, sent), block)
    }

    private companion object {
        const val PREFIX = "surefold.annotation."
    }
}
