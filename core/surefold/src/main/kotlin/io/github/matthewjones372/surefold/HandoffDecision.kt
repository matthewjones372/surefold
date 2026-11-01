package io.github.matthewjones372.surefold

import java.util.concurrent.atomic.AtomicReference

/** A final ownership decision with no observers after publication. */
class HandoffDecision<A : Any> {
    private val answer = AtomicReference<A?>()

    /** Publishes [value] once; false leaves the original reference unchanged. */
    fun tryComplete(value: A): Boolean = answer.compareAndSet(null, value)

    /** The winning reference or null while pending; does not wait or join its owner. */
    fun peek(): A? = answer.get()
}
