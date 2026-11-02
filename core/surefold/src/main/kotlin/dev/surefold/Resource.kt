package dev.surefold

import dev.surefold.fail.Failed
import java.util.concurrent.CancellationException

/** How the block of a [resourceScope] left, as its release actions are told it. */
sealed class ExitCase {
    /** The block returned a value, or raised a declared error — either way its caller was handed one. */
    data object Completed : ExitCase()

    /** The block was interrupted, which is the only cancellation the JDK has. */
    data class Cancelled(val interrupt: InterruptedException) : ExitCase()

    /** The block threw something nobody declared. */
    data class Failure(val failure: Throwable) : ExitCase()
}

/** A resource as a value: the acquisition and its release, waiting for a scope to run them in. */
typealias Resource<A> = ResourceScope.() -> A

/** The capability of acquiring something that has to be given back, and of saying how. */
interface ResourceScope {

    /** Runs another [Resource] in this scope, so its releases run with this scope's. */
    fun <A> Resource<A>.bind(): A = this()

    /** Acquires an [A] and installs the [release] this scope will run when it ends. */
    fun <A> install(acquire: () -> A, release: (A, ExitCase) -> Unit): A =
        acquire().also { acquired -> onRelease { release(acquired, it) } }

    /** Installs a release for something this scope does not hold a value of. */
    infix fun onRelease(release: (ExitCase) -> Unit)
}

/** A [Resource] value, for handing what a scope would acquire to something that opens one later. */
fun <A> resource(block: ResourceScope.() -> A): Resource<A> = block

/**
 * Runs [block] in a scope of its own, releasing everything installed in it in reverse acquisition order on
 * the way out — by return, raise, throw or interrupt.
 */
fun <A> resourceScope(block: ResourceScope.() -> A): A {
    val scope = Resources()
    val value = try {
        scope.block()
    } catch (t: Throwable) {
        // The block's own throw is the one that carries on; a release that also throws rides on it.
        throw scope.releaseAll(exitCaseOf(t), t) ?: t
    }
    val releaseFailed = scope.releaseAll(ExitCase.Completed, null)
    if (releaseFailed != null) throw releaseFailed
    return value
}

/** Acquires this resource, runs [block] with it, and releases it on the way out. */
infix fun <A, B> Resource<A>.use(block: (A) -> B): B = resourceScope { block(bind()) }

/**
 * A fail unwinds as surefold's [Failed], and a cancellation as a `CancellationException`, and neither is an
 * interrupt or a failure: the boundary it leaves through answers its caller with a value rather than a throw.
 */
private fun exitCaseOf(failure: Throwable): ExitCase = when (failure) {
    is InterruptedException -> ExitCase.Cancelled(failure)
    is CancellationException, is Failed -> ExitCase.Completed
    else -> ExitCase.Failure(failure)
}

private class Resources : ResourceScope {

    // Only the thread that opened the scope installs into this; work forked from the block opens one of its own.
    private val releases = mutableListOf<(ExitCase) -> Unit>()

    override fun onRelease(release: (ExitCase) -> Unit) {
        releases += release
    }

    /**
     * Every release runs, in reverse acquisition order, whatever the ones before it did: the throw that
     * comes back is [failure] if there was one, with any release's own throw suppressed onto it.
     */
    fun releaseAll(exit: ExitCase, failure: Throwable?): Throwable? =
        releases.asReversed().fold(failure) { carried, release -> carried.orThrowFrom { release(exit) } }
}

/** [release] run for its effect, answering with the failure carried so far, or with its own if it throws. */
private fun Throwable?.orThrowFrom(release: () -> Unit): Throwable? =
    try {
        release()
        this
    } catch (t: Throwable) {
        this?.also { it.addSuppressed(t) } ?: t
    }
