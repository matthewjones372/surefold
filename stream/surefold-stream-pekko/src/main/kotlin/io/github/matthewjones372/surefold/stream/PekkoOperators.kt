package io.github.matthewjones372.surefold.stream

import dev.surefold.fail.Either
import dev.surefold.fail.fold
import org.apache.pekko.NotUsed
import org.apache.pekko.japi.pf.PFBuilder
import org.apache.pekko.stream.Graph
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.SourceShape
import org.apache.pekko.stream.javadsl.Flow
import org.apache.pekko.stream.javadsl.Sink
import org.apache.pekko.stream.javadsl.Source
import scala.PartialFunction
import java.util.concurrent.CompletionStage

/** Every `Left` reaches [to] and every `Right` carries on: `divertTo` with no predicate to write. */
fun <L : Any, R : Any> Pipe.Companion.divertLefts(to: Sink<L, *>): Pipe<Nothing, Either<L, R>, R> {
    // Flipped, so that both branches read their element through the one fold `decided` is.
    val lefts = Flow.fromFunction<Either<L, R>, L> { either -> either.swap().decided() }.to(to)
    val at = pekkoSite()
    val right = guarded("divertLefts", at) { either: Either<L, R> -> either.decided() }
    val flow = Flow.create<Either<L, R>>().divertTo(lefts) { either -> either.isLeft() }.map { either -> right(either) }
    return Pipe(Node.Stage(Node.Hole, flow, Pekko, "divertLefts", at))
}

fun <E, In, L : Any, R : Any> Pipe<E, In, Either<L, R>>.divertLefts(to: Sink<L, *>): Pipe<E, In, R> =
    via(Pipe.divertLefts(to))

/**
 * Pekko drops a `null` completion before any operator downstream can see it, so the guard belongs
 * inside the stage. The cast is what lets Kotlin look at a value whose type already claims it
 * cannot be null.
 *
 * The facts are attached to a stage that failed before the null is looked for, so that the
 * throwable the library raises below carries them in its message and the caller's own carries
 * them beside it.
 */
@Suppress("UNCHECKED_CAST")
internal fun <B : Any> CompletionStage<B>.orDieOnNull(element: Any, at: String): CompletionStage<B> =
    (this as CompletionStage<B?>)
        .whenComplete { _, thrown -> thrown?.unwrapped()?.describedBy("mapAsync", element, at) }
        .thenApply { b ->
            b ?: throw NullPointerException("${facts("mapAsync", element, at)}: the stage completed with null")
        }

/**
 * The side a `divertTo` predicate has already settled. Pekko's split keeps one element type on both
 * branches, so this is what the predicate knows and the compiler cannot.
 *
 * A fold rather than a cast: nothing is unchecked, and an element on the branch the predicate did not
 * choose dies where it is rather than being dropped by a partial function that does not match it.
 */
internal fun <A : Any> Either<*, A>.decided(): A =
    fold({ throw IllegalStateException("divertLefts: $it reached the branch the predicate did not send it to") }) { it }

/**
 * A recovery that sees a declared failure and nothing else, so every other throwable dies.
 *
 * The graph type is written out rather than left as the `Source` it is built from: `Flow`'s
 * `recoverWithRetries` asks for exactly that type where `Source`'s takes anything extending it.
 */
internal fun <E, A : Any> onDeclaredFailure(
    f: (E) -> Source<A, NotUsed>,
): PartialFunction<Throwable, Graph<SourceShape<A>, NotUsed>> =
    PFBuilder<Throwable, Graph<SourceShape<A>, NotUsed>>()
        .match(DeclaredFailure::class.java) { failure -> f(failure.declared()) }
        .build()

/** Every `Left` reaches [to] and every `Right` carries on: `divertTo` with no predicate to write. */
fun <E, L : Any, R : Any> Stream<E, Either<L, R>>.divertLefts(to: Sink<L, *>): Stream<E, R> =
    via(Pipe.divertLefts(to))

/** Room for [size] elements between a fast producer and a slow consumer, and what to do when it fills. */
fun <A : Any> Pipe.Companion.buffer(size: Int, strategy: OverflowStrategy): Pipe<Nothing, A, A> =
    Pipe(Node.Stage(Node.Hole, Flow.create<A>().buffer(size, strategy), Pekko, "buffer", pekkoSite()))

fun <E, In, Out : Any> Pipe<E, In, Out>.buffer(size: Int, strategy: OverflowStrategy): Pipe<E, In, Out> =
    via(Pipe.buffer(size, strategy))

fun <E, A : Any> Stream<E, A>.buffer(size: Int, strategy: OverflowStrategy): Stream<E, A> =
    via(Pipe.buffer(size, strategy))
