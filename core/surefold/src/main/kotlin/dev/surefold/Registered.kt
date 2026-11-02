package dev.surefold

import java.util.ServiceConfigurationError

/**
 * The first registered [A], or [fallback] where loading one fails.
 *
 * A service file naming a class that cannot link — an adapter whose own dependency is not on the
 * classpath is the way this happens — makes `ServiceLoader` throw while instantiating it. That would
 * land on whoever wrote the first log line or took the first measurement, which is the one place an
 * observability problem must never surface.
 *
 * The fallback is said out loud, because a service quietly recording nothing is the same outcome as
 * this catching nothing.
 */
internal fun <A> firstRegistered(what: String, fallback: A, load: () -> A?): A = try {
    load() ?: fallback
} catch (failed: ServiceConfigurationError) {
    said(what, fallback, failed)
} catch (failed: LinkageError) {
    said(what, fallback, failed)
}

private fun <A> said(what: String, fallback: A, failed: Throwable): A {
    System.err.println("surefold: a registered $what could not be loaded, so the default is used: $failed")
    return fallback
}
