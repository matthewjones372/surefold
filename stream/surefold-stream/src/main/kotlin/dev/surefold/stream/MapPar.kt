package dev.surefold.stream

import dev.surefold.Limit
import dev.surefold.VirtualThreads
import dev.surefold.fail.Fail
import java.util.concurrent.Executor

/**
 * Up to [parallelism] element bodies run at once, each on a virtual thread of its own and in a `Fail<E>`; the results
 * keep the input's order, as [mapAsync]'s do.
 */
fun <E, A : Any, B : Any> Stream<E, A>.mapPar(parallelism: Int, f: Fail<E>.(A) -> B): Stream<E, B> =
    via(Pipe.mapPar(parallelism, VirtualThreads, f))

/** The same, with every body run on [on]. */
fun <E, A : Any, B : Any> Stream<E, A>.mapPar(parallelism: Int, on: Executor, f: Fail<E>.(A) -> B): Stream<E, B> =
    via(Pipe.mapPar(parallelism, on, f))

/**
 * Up to [parallelism] bodies at once, as [mapPar]'s; each result passes on once every earlier element with the same
 * [key] has. Elements with different keys pass each other.
 */
fun <E, A : Any, B : Any> Stream<E, A>.mapParKeyed(
    parallelism: Int,
    key: (A) -> Any,
    f: Fail<E>.(A) -> B,
): Stream<E, B> {
    require(parallelism > 0) { "mapParKeyed needs room for at least one body, and was given $parallelism" }
    return via(Pipe(Node.MapPar(Node.Hole, parallelism, VirtualThreads, f.erased(), buildSite(), key.erased())))
}

/**
 * As [mapPar], but each result passes on as soon as its body is done (spec 0170): a slow body holds back only itself.
 * Up to [parallelism] bodies at once, as ever.
 */
fun <E, A : Any, B : Any> Stream<E, A>.mapParUnordered(parallelism: Int, f: Fail<E>.(A) -> B): Stream<E, B> =
    via(Pipe.mapParUnordered(parallelism, VirtualThreads, f))

/** The same, with every body run on [on]. */
fun <E, A : Any, B : Any> Stream<E, A>.mapParUnordered(
    parallelism: Int,
    on: Executor,
    f: Fail<E>.(A) -> B,
): Stream<E, B> = via(Pipe.mapParUnordered(parallelism, on, f))

/** The starting form of [mapParUnordered]. */
fun <E, A : Any, B : Any> Pipe.Companion.mapParUnordered(
    parallelism: Int,
    on: Executor = VirtualThreads,
    f: Fail<E>.(A) -> B,
): Pipe<E, A, B> = Pipe(Node.MapPar(Node.Hole, parallelism, on, f.erased(), buildSite(), ordered = false))

fun <E, In, Out : Any, Out2 : Any> Pipe<E, In, Out>.mapParUnordered(
    parallelism: Int,
    f: Fail<E>.(Out) -> Out2,
): Pipe<E, In, Out2> = via(Pipe.mapParUnordered(parallelism, VirtualThreads, f))

/**
 * Bodies under a [Limit] (spec 0171): as many run at once as the limit allows now, and never more than its [Limit.max].
 * An adaptive limit finds that number itself, from how long the bodies take, and one shared limit bounds every stream
 * and call that draws on the same dependency together.
 */
fun <E, A : Any, B : Any> Stream<E, A>.mapPar(limit: Limit, f: Fail<E>.(A) -> B): Stream<E, B> =
    mapPar(limit.max) { a -> limit.withPermit { f(a) } }

/** [mapParUnordered] under a [Limit], as [mapPar] is above. */
fun <E, A : Any, B : Any> Stream<E, A>.mapParUnordered(limit: Limit, f: Fail<E>.(A) -> B): Stream<E, B> =
    mapParUnordered(limit.max) { a -> limit.withPermit { f(a) } }

/** The starting form: a pipe of bodies, each on a virtual thread of its own. */
fun <E, A : Any, B : Any> Pipe.Companion.mapPar(parallelism: Int, f: Fail<E>.(A) -> B): Pipe<E, A, B> =
    forked(parallelism, VirtualThreads, f)

/** The same, with every body run on [on]. */
fun <E, A : Any, B : Any> Pipe.Companion.mapPar(
    parallelism: Int,
    on: Executor,
    f: Fail<E>.(A) -> B,
): Pipe<E, A, B> = forked(parallelism, on, f)

fun <E, In, Out : Any, Out2 : Any> Pipe<E, In, Out>.mapPar(
    parallelism: Int,
    f: Fail<E>.(Out) -> Out2,
): Pipe<E, In, Out2> = via(Pipe.mapPar(parallelism, VirtualThreads, f))

/** The same, with every body run on [on]. */
fun <E, In, Out : Any, Out2 : Any> Pipe<E, In, Out>.mapPar(
    parallelism: Int,
    on: Executor,
    f: Fail<E>.(Out) -> Out2,
): Pipe<E, In, Out2> = via(Pipe.mapPar(parallelism, on, f))

/**
 * As [mapPar], on a stream that has yet to name a failure: this one reads the failure out of the body.
 *
 * It has a name of its own, `mapOrFail`'s suffix, because Kotlin fixes the type variable before it looks at the body.
 * Under one name, the form that reads `F` from a `raise` wins on a `Stream<Nothing, A>`, and a body that never raises
 * leaves it nothing to read.
 */
// The two erase to one JVM signature, so one of them needs a name of its own down there. Kotlin
// callers never see it.
@JvmName("mapParOrFailDeclaring")
fun <F, A : Any, B : Any> Stream<Nothing, A>.mapParOrFail(parallelism: Int, f: Fail<F>.(A) -> B): Stream<F, B> =
    via(Pipe.mapPar(parallelism, VirtualThreads, f))

/** The same, with every body run on [on]. */
@JvmName("mapParOrFailDeclaringOn")
fun <F, A : Any, B : Any> Stream<Nothing, A>.mapParOrFail(
    parallelism: Int,
    on: Executor,
    f: Fail<F>.(A) -> B,
): Stream<F, B> = via(Pipe.mapPar(parallelism, on, f))

/** As above, for a stream whose failure type is already named: [mapPar] under the name that says it may fail. */
fun <E, A : Any, B : Any> Stream<E, A>.mapParOrFail(parallelism: Int, f: Fail<E>.(A) -> B): Stream<E, B> =
    mapPar(parallelism, f)

/** The same, with every body run on [on]. */
fun <E, A : Any, B : Any> Stream<E, A>.mapParOrFail(
    parallelism: Int,
    on: Executor,
    f: Fail<E>.(A) -> B,
): Stream<E, B> = mapPar(parallelism, on, f)

/** As the stream pair above, on a pipe that has yet to name a failure. */
@JvmName("mapParOrFailPipeDeclaring")
fun <F, In, Out : Any, Out2 : Any> Pipe<Nothing, In, Out>.mapParOrFail(
    parallelism: Int,
    f: Fail<F>.(Out) -> Out2,
): Pipe<F, In, Out2> = via(Pipe.mapPar(parallelism, VirtualThreads, f))

/** The same, with every body run on [on]. */
@JvmName("mapParOrFailPipeDeclaringOn")
fun <F, In, Out : Any, Out2 : Any> Pipe<Nothing, In, Out>.mapParOrFail(
    parallelism: Int,
    on: Executor,
    f: Fail<F>.(Out) -> Out2,
): Pipe<F, In, Out2> = via(Pipe.mapPar(parallelism, on, f))

fun <E, In, Out : Any, Out2 : Any> Pipe<E, In, Out>.mapParOrFail(
    parallelism: Int,
    f: Fail<E>.(Out) -> Out2,
): Pipe<E, In, Out2> = mapPar(parallelism, f)

/** The same, with every body run on [on]. */
fun <E, In, Out : Any, Out2 : Any> Pipe<E, In, Out>.mapParOrFail(
    parallelism: Int,
    on: Executor,
    f: Fail<E>.(Out) -> Out2,
): Pipe<E, In, Out2> = mapPar(parallelism, on, f)

private fun <E, A : Any, B : Any> forked(parallelism: Int, on: Executor, f: Fail<E>.(A) -> B): Pipe<E, A, B> =
    Pipe(Node.MapPar(Node.Hole, parallelism, on, f.erased(), buildSite()))
