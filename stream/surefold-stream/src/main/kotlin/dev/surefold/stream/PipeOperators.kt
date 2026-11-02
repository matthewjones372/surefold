package dev.surefold.stream

import dev.surefold.fail.Either
import java.util.concurrent.CompletionStage

/**
 * Up to [parallelism] stages run at once and their results keep the input's order.
 *
 * A stage that completes with `null` dies here. Pekko's own `mapAsync` documents that such an
 * element "is not passed downstream", and a lookup lifted into a future is the easiest thing in
 * Kotlin to write and the hardest to notice.
 */
fun <A : Any, B : Any> Pipe.Companion.mapAsync(parallelism: Int, f: (A) -> CompletionStage<B>): Pipe<Nothing, A, B> =
    Pipe(Node.MapAsync(Node.Hole, parallelism, f.erased(), buildSite()))

fun <E, In, Out : Any, Out2 : Any> Pipe<E, In, Out>.mapAsync(
    parallelism: Int,
    f: (Out) -> CompletionStage<Out2>,
): Pipe<E, In, Out2> = via(Pipe.mapAsync(parallelism, f))

/** The starting form of [mapAsyncUnordered]. */
fun <A : Any, B : Any> Pipe.Companion.mapAsyncUnordered(
    parallelism: Int,
    f: (A) -> CompletionStage<B>,
): Pipe<Nothing, A, B> = Pipe(Node.MapAsync(Node.Hole, parallelism, f.erased(), buildSite(), ordered = false))

fun <E, In, Out : Any, Out2 : Any> Pipe<E, In, Out>.mapAsyncUnordered(
    parallelism: Int,
    f: (Out) -> CompletionStage<Out2>,
): Pipe<E, In, Out2> = via(Pipe.mapAsyncUnordered(parallelism, f))

/**
 * A backlog collapsed into one element: [seed] starts an aggregate from the element downstream was
 * not ready for, and [aggregate] folds each later one into it until downstream asks again.
 *
 * Both halves run caller code, so both are guarded, and the element a defect names is the one being
 * folded in. Nothing is dropped: what an aggregate leaves out is what the caller left out of it.
 */
fun <A : Any, S : Any> Pipe.Companion.conflateWithSeed(seed: (A) -> S, aggregate: (S, A) -> S): Pipe<Nothing, A, S> =
    Pipe(Node.Conflate(Node.Hole, seed.erased(), aggregate.erased(), buildSite()))

fun <E, In, Out : Any, S : Any> Pipe<E, In, Out>.conflateWithSeed(
    seed: (Out) -> S,
    aggregate: (S, Out) -> S,
): Pipe<E, In, S> = via(Pipe.conflateWithSeed(seed, aggregate))

/** Each element's own elements, in its order; one that answers with none emits none. */
fun <A : Any, B : Any> Pipe.Companion.mapConcat(f: (A) -> Iterable<B>): Pipe<Nothing, A, B> =
    Pipe(Node.MapConcat(Node.Hole, f.erased(), buildSite()))

fun <E, In, Out : Any, B : Any> Pipe<E, In, Out>.mapConcat(f: (Out) -> Iterable<B>): Pipe<E, In, B> =
    via(Pipe.mapConcat(f))

/** The declared failure becomes the last element, as a `Left`, leaving none for the type to carry. */
fun <E, A : Any> Pipe.Companion.either(): Pipe<Nothing, A, Either<E, A>> = Pipe(Node.Either(Node.Hole))

fun <E, In, Out : Any> Pipe<E, In, Out>.either(): Pipe<Nothing, In, Either<E, Out>> = replacing(Pipe.either<E, Out>())

/** A `Left` fails the pipe with what it holds; a `Right` carries on as the element. */
fun <E, L : E, R : Any> Pipe.Companion.absolve(): Pipe<E, Either<L, R>, R> = Pipe(Node.Absolve(Node.Hole, buildSite()))

fun <E, In, L : E, R : Any> Pipe<E, In, Either<L, R>>.absolve(): Pipe<E, In, R> = via(Pipe.absolve<E, L, R>())

/** Handles a declared failure only: a defect is nothing anyone declared, and still dies. */
fun <E, E2, A : Any> Pipe.Companion.catchAll(f: (E) -> Stream<E2, A>): Pipe<E2, A, A> {
    val recover: (E) -> Node = { e -> f(e).node }
    return Pipe(Node.CatchAll(Node.Hole, recover.erased()))
}

fun <E, E2, In, Out : Any> Pipe<E, In, Out>.catchAll(f: (E) -> Stream<E2, Out>): Pipe<E2, In, Out> =
    replacing(Pipe.catchAll(f))

/**
 * The declared failure as another one. `catchAll` says this too, and says recovery while it does: a
 * reader of `catchAll { Stream.failed(it.toIngestError()) }` reaches the end before learning that
 * nothing was recovered.
 */
fun <E, E2, A : Any> Pipe.Companion.mapError(f: (E) -> E2): Pipe<E2, A, A> =
    Pipe(Node.MapError(Node.Hole, f.erased(), buildSite()))

fun <E, E2, In, Out : Any> Pipe<E, In, Out>.mapError(f: (E) -> E2): Pipe<E2, In, Out> =
    replacing(Pipe.mapError(f))

/** ZIO's `orElse` rather than Pekko's: [other] takes over on a failure, not on an empty stream. */
fun <E, A : Any> Pipe.Companion.orElse(other: Stream<E, A>): Pipe<E, A, A> = catchAll<E, E, A> { other }

fun <E, In, Out : Any> Pipe<E, In, Out>.orElse(other: Stream<E, Out>): Pipe<E, In, Out> =
    replacing(Pipe.orElse(other))
