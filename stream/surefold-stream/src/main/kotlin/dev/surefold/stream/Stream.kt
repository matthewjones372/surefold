package dev.surefold.stream

import dev.surefold.fail.Fail
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.time.Duration

/**
 * A stream of `A` that can end with a declared failure of `E`, described and
 * not yet running.
 */
class Stream<out E, out A : Any> @StreamSpi constructor(@property:StreamSpi val node: Node) {

    /** What a backend compiled [node] to, kept, so a stream described once and run per request compiles once. */
    @property:StreamSpi
    val compiled = CompileCache()

    companion object
}

/**
 * A declared failure on Pekko's failure channel, unwrapped only by `run`.
 *
 * No stack trace: the error is the value being carried, and filling one in
 * would charge every declared failure for a diagnostic nobody reads.
 */
@StreamSpi
class DeclaredFailure(val error: Any?) : RuntimeException(null, null, false, false)

/**
 * The error a [DeclaredFailure] carries, read back as the stream's own `E`.
 *
 * The cast is unchecked because the channel the error travelled in carries a
 * `Throwable` and nothing narrower; the stream's own type is what says what
 * came back out of it.
 */
@Suppress("UNCHECKED_CAST")
@StreamSpi
fun <E> DeclaredFailure.declared(): E = error as E

/**
 * The scope `mapOrFail` runs in: a `Fail<E>`, so `bind`, `ensure` and surefold's
 * own combinators are in reach of an element body, and `fail` returns Nothing,
 * so it sits after an Elvis.
 */
class Failing<in E> @StreamSpi constructor() : Fail<E> {

    /** The failure travels as it always has: the wrapper only `run` unwraps. */
    override fun fail(error: E): Nothing = throw DeclaredFailure(error)
}

fun <A : Any> Stream.Companion.from(elements: Iterable<A>): Stream<Nothing, A> = Stream(Node.Elements(elements))

/** The one element named, and `A : Any` is where a nullable is refused so the `?:` is written at the lookup. */
fun <A : Any> Stream.Companion.single(element: A): Stream<Nothing, A> = Stream(Node.Single(element))

/** The elements named, in order; with none of them it is [empty]. */
fun <A : Any> Stream.Companion.of(vararg elements: A): Stream<Nothing, A> = Stream(Node.Elements(elements.asList()))

/** [element] every [every], the first one [after] the run starts, and no `Cancellable` to unwrap. */
fun <A : Any> Stream.Companion.tick(every: Duration, element: A, after: Duration = every): Stream<Nothing, A> =
    Stream(Node.Tick(every, element, after))

/**
 * The stage's value as the one element; a completion with `null` is a defect naming this builder.
 *
 * A stage from Java can complete with `null` whatever its type argument says, and Pekko reads that as a
 * source with nothing in it — the empty stream this builder exists so that nobody gets by accident.
 */
fun <A : Any> Stream.Companion.fromStage(stage: CompletionStage<A>): Stream<Nothing, A> =
    Stream(Node.FromStage(stage) { NullPointerException(NULL_COMPLETION) })

/** As above, with the absence named: a completion with `null` is the declared failure [ifNull]. */
fun <E, A : Any> Stream.Companion.fromStage(stage: CompletionStage<A>, ifNull: E): Stream<E, A> =
    Stream(Node.FromStage(stage) { DeclaredFailure(ifNull) })

fun <E> Stream.Companion.fail(error: E): Stream<E, Nothing> = Stream(Node.Fail(error))

fun Stream.Companion.empty(): Stream<Nothing, Nothing> = Stream(Node.Empty)

fun <E, A : Any, B : Any> Stream<E, A>.map(f: (A) -> B): Stream<E, B> = Stream(Node.Map(node, f.erased(), buildSite()))

/**
 * `f` may answer with `fail(e)`, which ends the stream with the `E` it names.
 *
 * There are two of these because Kotlin fixes a type variable the moment the
 * receiver constrains it: on a `Stream<Nothing, A>` a single signature would
 * pin the failure type to `Nothing` before the `fail(e)` naming it is looked
 * at. This one reads the failure type out of the body, and the one below keeps
 * the failure a stream already declares. The more specific receiver decides,
 * so a call site never picks between them.
 */
// The two erase to one JVM signature, so one of them needs a name of its own
// down there. Kotlin callers never see it.
@JvmName("mapOrFailDeclaring")
fun <F, A : Any, B : Any> Stream<Nothing, A>.mapOrFail(f: Failing<F>.(A) -> B): Stream<F, B> =
    Stream(Node.MapOrFail(node, f.erased(), buildSite()))

/** As above, for a stream whose failure type is already named. */
fun <E, A : Any, B : Any> Stream<E, A>.mapOrFail(f: Failing<E>.(A) -> B): Stream<E, B> =
    Stream(Node.MapOrFail(node, f.erased(), buildSite()))

fun <E, A : Any> Stream<E, A>.filter(predicate: (A) -> Boolean): Stream<E, A> =
    Stream(Node.Filter(node, predicate.erased(), buildSite()))

/** A defect that does not say where it came from is the disappearance again, so the builder is in the message. */
private const val NULL_COMPLETION = "Stream.fromStage: the stage completed with null"

/**
 * The stage a source can be built on: one that fails where the given one completes with `null`.
 *
 * The guard is here rather than downstream because Pekko never offers the null to an operator, and the
 * cast is what lets Kotlin look at a value whose type already claims it cannot be null. Nothing waits on
 * the stage: what completes it runs this, so no Pekko thread is spent on a value that has not arrived.
 */
@Suppress("UNCHECKED_CAST")
@StreamSpi
fun <A : Any> CompletionStage<A>.checked(onNull: () -> Throwable): CompletionStage<A> {
    val checked = CompletableFuture<A>()
    (this as CompletionStage<A?>).whenComplete { value, thrown ->
        when {
            thrown != null -> checked.completeExceptionally(thrown.unwrapped())
            value == null -> checked.completeExceptionally(onNull())
            else -> checked.complete(value)
        }
    }
    return checked
}
