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

/**
 * Every [SurefoldLocal]'s value on this thread (spec 0274). A block binds a [Frame] as a `ScopedValue`; `attach`,
 * which has no block, replaces the map in the frame bound around it. A thread nothing has bound on keeps what it
 * attached in [root].
 */
internal object Bindings {

    private val frame: ScopedValue<Frame> = ScopedValue.newInstance()
    private val root = ThreadLocal<Map<SurefoldLocal<*>, Any?>>()

    // What orElse answers where no frame is bound: one lookup rather than isBound and then get. Never written.
    private val unbound = Frame(emptyMap())

    fun snapshot(): Map<SurefoldLocal<*>, Any?> {
        val bound = frame.orElse(unbound)
        return if (bound !== unbound) bound.bound else root.get() ?: emptyMap()
    }

    fun rebind(bound: Map<SurefoldLocal<*>, Any?>) {
        val current = frame.orElse(unbound)
        when {
            current !== unbound -> current.bound = bound
            bound.isEmpty() -> root.remove()
            else -> root.set(bound)
        }
    }

    fun <B> under(bound: Map<SurefoldLocal<*>, Any?>, block: () -> B): B =
        ScopedValue.where(frame, Frame(bound)).call(ScopedValue.CallableOp<B, RuntimeException> { block() })

    /** One block's bindings. Only the thread that bound it reads or writes it: a fork binds a frame of its own. */
    private class Frame(var bound: Map<SurefoldLocal<*>, Any?>)
}
