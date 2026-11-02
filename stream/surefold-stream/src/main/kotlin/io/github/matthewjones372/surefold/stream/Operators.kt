package io.github.matthewjones372.surefold.stream

import dev.surefold.fail.Either
import java.util.concurrent.CompletionStage

/**
 * Up to [parallelism] stages run at once and their results keep the input's order.
 *
 * A stage that completes with `null` dies here. Pekko's own `mapAsync` documents
 * that such an element "is not passed downstream", and a lookup lifted into a
 * future is the easiest thing in Kotlin to write and the hardest to notice.
 */
fun <E, A : Any, B : Any> Stream<E, A>.mapAsync(parallelism: Int, f: (A) -> CompletionStage<B>): Stream<E, B> =
    via(Pipe.mapAsync(parallelism, f))

/** As [mapAsync], but each result passes on as soon as its stage completes (spec 0170), Pekko's name and order. */
fun <E, A : Any, B : Any> Stream<E, A>.mapAsyncUnordered(
    parallelism: Int,
    f: (A) -> CompletionStage<B>,
): Stream<E, B> = via(Pipe.mapAsyncUnordered(parallelism, f))

/** A backlog collapsed into one element while downstream is busy, and nothing dropped to do it. */
fun <E, A : Any, S : Any> Stream<E, A>.conflateWithSeed(seed: (A) -> S, aggregate: (S, A) -> S): Stream<E, S> =
    via(Pipe.conflateWithSeed(seed, aggregate))

/** Each element's own elements, in its order; one that answers with none emits none. */
fun <E, A : Any, B : Any> Stream<E, A>.mapConcat(f: (A) -> Iterable<B>): Stream<E, B> = via(Pipe.mapConcat(f))

/** Pekko's own order: [first]'s elements before this stream's, and a failure in either ends the run. */
fun <E, A : Any> Stream<E, A>.prepend(first: Stream<E, A>): Stream<E, A> = Stream(Node.Prepend(node, first.node))

/** The mirror of [prepend], in Pekko's order too: [next]'s elements after this stream's. */
fun <E, A : Any> Stream<E, A>.concat(next: Stream<E, A>): Stream<E, A> = Stream(Node.Concat(node, next.node))

/** The declared failure becomes the last element, as a `Left`, leaving none for the type to carry. */
fun <E, A : Any> Stream<E, A>.either(): Stream<Nothing, Either<E, A>> = replacing(Pipe.either<E, A>())

/** A `Left` fails the stream with what it holds; a `Right` carries on as the element. */
fun <E, L : E, R : Any> Stream<E, Either<L, R>>.absolve(): Stream<E, R> = via(Pipe.absolve<E, L, R>())

/** Handles a declared failure only: a defect is nothing anyone declared, and still dies. */
fun <E, E2, A : Any> Stream<E, A>.catchAll(f: (E) -> Stream<E2, A>): Stream<E2, A> = replacing(Pipe.catchAll(f))

/** ZIO's `orElse` rather than Pekko's: [other] takes over on a failure, not on an empty stream. */
fun <E, E2, A : Any> Stream<E, A>.mapError(f: (E) -> E2): Stream<E2, A> = replacing(Pipe.mapError(f))

fun <E, A : Any> Stream<E, A>.orElse(other: Stream<E, A>): Stream<E, A> = replacing(Pipe.orElse(other))

/**
 * A stream that ends having emitted nothing fails with [error] instead; one that emitted is untouched.
 *
 * Pekko's `orElse` switches to the alternative only where the primary completed without an element, so
 * a failure of its own reaches `run` as itself. On `Stream` alone: a pipe is spliced into a source it
 * cannot see, so it has no way to know whether that source emitted.
 */
fun <E : E2, E2, A : Any> Stream<E, A>.orFailIfEmpty(error: E2): Stream<E2, A> =
    Stream(Node.OrFailIfEmpty(node, error))
