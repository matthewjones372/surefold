package dev.surefold.fail

import java.util.concurrent.CancellationException

/**
 * Returns this throwable when it is one a caller may handle, and rethrows it when it is not: a VM error, a
 * broken link, an interrupt, a cancellation, or a [fail] on its way out of its block.
 */
fun Throwable.nonFatalOrThrow(): Throwable = when (this) {
    is VirtualMachineError, is LinkageError, is InterruptedException, is CancellationException, is Failed -> throw this
    else -> this
}
