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
        private val installed = ThreadLocal<Waiting?>()

        /** The [Waiting] of the worker this thread is, or `null` where blocking the thread is right. */
        fun here(): Waiting? = installed.get()

        /** [body] with [waiting] installed on this thread. */
        fun <T> within(waiting: Waiting, body: () -> T): T {
            val outer = installed.get()
            installed.set(waiting)
            return try {
                body()
            } finally {
                installed.set(outer)
            }
        }
    }
}
