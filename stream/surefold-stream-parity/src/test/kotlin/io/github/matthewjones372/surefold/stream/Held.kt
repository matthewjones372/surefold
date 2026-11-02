package io.github.matthewjones372.surefold.stream

import dev.surefold.Scope
import dev.surefold.scope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * One scope held open for the whole test JVM, for the backends that run on one. An invocation cannot sit inside a
 * scope's scope, so a daemon thread holds it, and the JVM's exit ends it.
 */
internal val heldScope: Scope<Nothing> by lazy {
    val opened = AtomicReference<Scope<Nothing>>()
    val ready = CountDownLatch(1)
    Thread.ofPlatform().daemon().start {
        scope {
            opened.set(this)
            ready.countDown()
            CountDownLatch(1).await()
        }
    }
    ready.await()
    opened.get()
}
