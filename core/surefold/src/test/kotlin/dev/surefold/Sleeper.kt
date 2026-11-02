package dev.surefold

import dev.surefold.assert.shouldNotBeNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// Long enough that a scope which waited a fork out instead of interrupting it
// would blow the bound below, rather than pass by luck on a slow machine.
internal const val NEVER_FINISHES_MILLIS = 20_000L
internal const val PROMPT_MILLIS = 10_000L

private const val RENDEZVOUS_SECONDS = 10L

internal data class Bad(val why: String)

internal class Boom : RuntimeException("boom")

/** A fork that reports whether it was interrupted, so no test has to time one. */
internal class Sleeper {
    private val started = CountDownLatch(1)
    private val thread = AtomicReference<Thread?>(null)
    private val interrupted = AtomicBoolean(false)

    fun body(): String {
        thread.set(Thread.currentThread())
        started.countDown()
        return try {
            Thread.sleep(NEVER_FINISHES_MILLIS)
            "slept"
        } catch (stop: InterruptedException) {
            interrupted.set(true)
            "interrupted"
        }
    }

    fun awaitStart(): Unit = started.await()

    fun wasInterrupted(): Boolean = interrupted.get()

    fun isAlive(): Boolean = thread.get().shouldNotBeNull().isAlive
}

/** A branch meant to lose a race: parked until it is interrupted, and then of the type the race wants. */
internal fun <T> Sleeper.losingWith(answer: T): T {
    body()
    return answer
}

/**
 * Every branch has to arrive before any of them leaves, so branches run one after another never get past
 * this; the bound is there to fail such a test rather than hang it.
 */
internal fun CountDownLatch.rendezvous(): Boolean {
    countDown()
    return await(RENDEZVOUS_SECONDS, TimeUnit.SECONDS)
}
