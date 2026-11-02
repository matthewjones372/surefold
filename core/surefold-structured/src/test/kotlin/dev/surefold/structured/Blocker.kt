package dev.surefold.structured

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** A fork body that waits until it is interrupted and says whether it was, so no test has to time one. */
internal class Blocker {
    private val started = CountDownLatch(1)
    private val never = CountDownLatch(1)
    private val interrupted = AtomicBoolean(false)

    fun body(): String {
        started.countDown()
        return try {
            never.await()
            "finished"
        } catch (stop: InterruptedException) {
            interrupted.set(true)
            throw stop
        }
    }

    fun awaitStart(): Unit = started.await()

    fun wasInterrupted(): Boolean = interrupted.get()
}
