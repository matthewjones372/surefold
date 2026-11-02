package io.github.matthewjones372.surefold.stream

/**
 * How a source waits for something another thread does, on a backend that must know it is waiting.
 *
 * Most backends need nothing: a source blocks its thread, and a stop wakes it. A backend whose workers
 * take turns cannot let one block, since the others wait for its turn to end; it installs a [Waiting] on
 * each worker, and a source that finds one waits through it instead.
 */
@StreamSpi
interface Waiting {

    /** Returns once [ready] is true, or once the run is stopping, whichever is first. */
    fun until(ready: () -> Boolean)

    /** Something a waiting [ready] reads was changed from outside the run: it is asked again. */
    fun changed()

    companion object {
        private val installed: ScopedValue<Waiting> = ScopedValue.newInstance()

        /** The [Waiting] of the worker this thread is, or `null` where blocking the thread is right. */
        fun here(): Waiting? = installed.orElse(Blocking).takeUnless { it === Blocking }

        /** What [installed] answers on a thread no backend installed one on: compared by identity, never waited on. */
        private object Blocking : Waiting {
            override fun until(ready: () -> Boolean) = error("Blocking is a marker, not a Waiting")

            override fun changed() = error("Blocking is a marker, not a Waiting")
        }

        /** [body] with [waiting] installed on this thread. */
        fun <T> within(waiting: Waiting, body: () -> T): T =
            ScopedValue.where(installed, waiting).call(ScopedValue.CallableOp<T, RuntimeException> { body() })
    }
}
