package io.github.matthewjones372.surefold.otel

import dev.surefold.SurefoldLocal
import dev.surefold.surefoldLocal
import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextStorage
import io.opentelemetry.context.ContextStorageProvider
import io.opentelemetry.context.Scope

/**
 * Where OpenTelemetry's current [Context] lives while this module is on the classpath.
 *
 * A fork inherits what its opener bound, so a span opened before a `parMap` is the parent of what each
 * branch opens — which a `ThreadLocal`, and so OpenTelemetry's own storage, cannot be.
 */
val otelContext: SurefoldLocal<Context> = surefoldLocal { Context.root() }

/** The storage itself: a [SurefoldLocal] behind the attach-and-detach pair OpenTelemetry asks for. */
class SurefoldContextStorage : ContextStorage {

    override fun attach(toAttach: Context): Scope {
        val detach = otelContext.attach(toAttach)
        return Scope { detach.detach() }
    }

    override fun current(): Context = otelContext.get()
}

/**
 * Registered through `META-INF/services`, which is process-wide: every library using OpenTelemetry's
 * context in this JVM reads and writes it here, whether or not it has heard of surefold. That is the point
 * — an HTTP client's span has to be the same span a forked handler continues — and it is also why this
 * lives in a module a service opts into rather than in `surefold`.
 */
class SurefoldContextStorageProvider : ContextStorageProvider {

    override fun get(): ContextStorage = SurefoldContextStorage()
}
