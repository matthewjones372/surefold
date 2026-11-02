package dev.surefold.stream

import java.util.concurrent.atomic.AtomicReference

/**
 * The tree a backend compiles. A pipeline never needs it; a backend in another module does, and it
 * changes whenever an operator does, which is what opting in acknowledges.
 */
@RequiresOptIn(message = "The node tree a stream backend compiles. It changes as the operators do.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FUNCTION)
annotation class StreamSpi

/** Which backend a native value belongs to: compared by identity, and named when another one refuses it. */
@StreamSpi
class BackendKey(val name: String) {
    override fun toString() = name
}

/**
 * One backend's compiled form of a description, kept. One slot, because a service runs on one backend:
 * a second backend compiles every time rather than paying for a map on every stream.
 */
@StreamSpi
class CompileCache internal constructor() {

    private val slot = AtomicReference<Pair<BackendKey, Any>?>(null)

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getOrCompile(by: BackendKey, compile: () -> T): T {
        slot.get()?.let { (key, value) -> if (key === by) return value as T }
        val built = compile()
        slot.compareAndSet(null, by to built)
        return built
    }
}
