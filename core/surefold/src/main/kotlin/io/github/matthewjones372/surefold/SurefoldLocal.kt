package io.github.matthewjones372.surefold

/**
 * A binding a fork inherits: what the opening thread had bound is what its branches read.
 *
 * A `ThreadLocal` is empty on a thread nothing bound it on, and every combinator here opens one.
 */
class SurefoldLocal<A> internal constructor(private val initial: () -> A) {

    fun get(): A {
        val bound = Bindings.snapshot()
        // Only `locally` writes the map, and it writes an A under this key.
        @Suppress("UNCHECKED_CAST")
        return if (bound.containsKey(this)) bound[this] as A else unbound()
    }

    /** What [get] answers where nothing is bound, which is what `main` reads, whatever is bound around the call. */
    fun unbound(): A = initial()

    /** Runs [block] with [value] bound, and puts back what was bound before on the way out. */
    fun <B> locally(value: A, block: () -> B): B = Bindings.under(Bindings.snapshot() + (this to value), block)

    /**
     * [locally] as a pair, for an integration whose own API attaches and detaches rather than taking a
     * block — OpenTelemetry's `ContextStorage` is written that way. Detach in the order attached.
     */
    fun attach(value: A): Detach {
        val before = Bindings.snapshot()
        Bindings.rebind(before + (this to value))
        return Detach { Bindings.rebind(before) }
    }
}

/** What puts back what [SurefoldLocal.attach] replaced. */
fun interface Detach {
    fun detach()
}

/** A [SurefoldLocal] answering with [initial] wherever nothing has been bound. */
fun <A> surefoldLocal(initial: () -> A): SurefoldLocal<A> = SurefoldLocal(initial)

internal object Bindings {

    private val current = ThreadLocal<Map<SurefoldLocal<*>, Any?>>()

    fun snapshot(): Map<SurefoldLocal<*>, Any?> = current.get() ?: emptyMap()

    fun rebind(bound: Map<SurefoldLocal<*>, Any?>) {
        if (bound.isEmpty()) current.remove() else current.set(bound)
    }

    fun <B> under(bound: Map<SurefoldLocal<*>, Any?>, block: () -> B): B {
        val before = current.get()
        current.set(bound)
        return try {
            block()
        } finally {
            if (before == null) current.remove() else current.set(before)
        }
    }
}
