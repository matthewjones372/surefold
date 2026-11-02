// In surefold-stream's package, so that importing `runCollect` from it imports these too, and a stream of
// Committed records is refused every run but the one that commits.
package dev.surefold.stream

import dev.surefold.kafka.Committed

private const val COMMIT = "A stream of Committed records ends on runCommitting, or its offsets are never committed."

@Deprecated(COMMIT, level = DeprecationLevel.ERROR)
fun <E, A : Any> Stream<E, Committed<A>>.runCollect(): Run<E, List<Committed<A>>> = error(COMMIT)

@Deprecated(COMMIT, level = DeprecationLevel.ERROR)
@Suppress("UnusedParameter") // The signature is what refuses the call; nothing can reach the body.
fun <E, A : Any, R : Any> Stream<E, Committed<A>>.runFold(zero: R, f: (R, Committed<A>) -> R): Run<E, R> =
    error(COMMIT)
