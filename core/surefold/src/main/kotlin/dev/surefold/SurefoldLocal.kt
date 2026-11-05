package dev.surefold

/**
 * A binding a fork inherits: what the opening thread had bound is what its branches read.
 *
 * A `ThreadLocal` is empty on a thread nothing bound it on, and every combinator here opens one.
 */
class SurefoldLocal<A> internal constructor(private val initial: () -> A) {

    fun get(): A = from(Bindings.snapshot())

    /**
     * What [get] answers, read from a chain already in hand: `logAt` reads several locals from one snapshot rather than
     * looking the thread's chain up once for each (spec 0284).
     */
    internal fun from(head: Bound?): A {
        var link = head
        while (link != null) {
            // Only `locally` and `attach` make a link for this local, and each holds an A.
            @Suppress("UNCHECKED_CAST") if (link.local === this) return link.value as A
            link = link.next
        }
        return unbound()
    }

    /** What [get] answers where nothing is bound, which is what `main` reads, whatever is bound around the call. */
    fun unbound(): A = initial()

    /** Runs [block] with [value] bound, and puts back what was bound before on the way out. */
    fun <B> locally(value: A, block: () -> B): B = Bindings.under(Bound(this, value, Bindings.snapshot()), block)

    /**
     * [locally] as a pair, for an integration whose own API attaches and detaches rather than taking a block —
     * OpenTelemetry's `ContextStorage` is written that way. Detach in the order attached.
     */
    fun attach(value: A): Detach {
        val before = Bindings.snapshot()
        // An attach that is never detached, as OpenFeature's propagator does once per request, replaces this
        // local's link rather than stacking another on a thread that lives for the whole process.
        Bindings.rebind(Bound(this, value, before.without(this)))
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
 * One binding, and the ones made before it (spec 0280). Binding pushes a link rather than copying every bound local,
 * and a fork captures the head. The first link for a local is its value; any further down are shadowed.
 */
internal class Bound(val local: SurefoldLocal<*>, val value: Any?, val next: Bound?)

/** This chain with every link for [local] left out: what an `attach` replaces. */
private fun Bound?.without(local: SurefoldLocal<*>): Bound? =
    when {
        this == null -> null

        this.local === local -> next.without(local)

        else -> {
            val rest = next.without(local)
            if (rest === next) this else Bound(this.local, value, rest)
        }
    }

internal object Bindings {

    private val current = ThreadLocal<Bound?>()

    fun snapshot(): Bound? = current.get()

    fun rebind(bound: Bound?) {
        if (bound == null) current.remove() else current.set(bound)
    }

    fun <B> under(bound: Bound?, block: () -> B): B {
        val before = current.get()
        current.set(bound)
        return try {
            block()
        } finally {
            if (before == null) current.remove() else current.set(before)
        }
    }
}
