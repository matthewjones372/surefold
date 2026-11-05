package dev.surefold

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

/**
 * Waits on the calling thread for this future and answers with its value: on a virtual thread that parks it and nothing
 * else. A failure arrives as the cause itself, and an interrupt cancels the future on the way out.
 */
fun <T> Future<T>.await(): T =
    try {
        get()
    } catch (failed: ExecutionException) {
        // `get` wraps whatever completed the future, peeling a CompletionException as it goes; what the
        // caller declared and catches is the cause, not either wrapper.
        throw failed.cause ?: failed
    } catch (stop: InterruptedException) {
        // Nobody is left to read the answer, so the future is told to stop rather than run on unwatched.
        cancel(true)
        throw stop
    }

/** The same wait for a stage. */
fun <T> CompletionStage<T>.await(): T = toCompletableFuture().await()

/** Only here because a `CompletableFuture` is both of the above, and neither would be picked over the other. */
fun <T> CompletableFuture<T>.await(): T = (this as Future<T>).await()
