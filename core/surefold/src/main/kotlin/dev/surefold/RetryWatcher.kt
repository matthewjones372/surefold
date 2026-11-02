package dev.surefold

import java.util.ServiceConfigurationError
import java.util.ServiceLoader

/**
 * Told of a step that committed after running more than once (spec 0212), with how many runs it took, how many of
 * them gave up on each cause, and whether the run that committed ran alone (spec 0231). Registered through
 * `META-INF/services`, as a [Carrier] is: `surefold-otel` adds it to the current span. A step that commits on its
 * first run tells nothing.
 */
interface RetryWatcher {
    fun committed(transact: String, attempts: Int, causes: Map<String, Int>, solo: Boolean)
}

internal object RetryWatchers {
    private val registered: List<RetryWatcher> by lazy {
        try {
            ServiceLoader.load(RetryWatcher::class.java, RetryWatcher::class.java.classLoader).toList()
        } catch (failed: ServiceConfigurationError) {
            System.err.println("surefold: a registered RetryWatcher could not be loaded, so none is told: $failed")
            emptyList()
        } catch (failed: LinkageError) {
            System.err.println("surefold: a registered RetryWatcher could not be loaded, so none is told: $failed")
            emptyList()
        }
    }

    fun committed(transact: String, attempts: Int, causes: Map<String, Int>, solo: Boolean) {
        registered.forEach { it.committed(transact, attempts, causes, solo) }
    }
}
