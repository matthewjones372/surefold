package dev.surefold.stream

/**
 * The middle of a pipeline: `In` in, `Out` out, and a declared failure of `E` it can end with.
 *
 * Described and not yet running, and reusable — one of these is spliced into as many streams as there are sources for
 * it. Every operator `Stream` has is here, and `Stream`'s are this one's through [via].
 */
class Pipe<out E, in In, out Out : Any> @StreamSpi constructor(@property:StreamSpi val node: Node) {

    /** What a backend compiled [node] to, kept, as a stream's is. */
    @property:StreamSpi val compiled = CompileCache()

    companion object
}

/** The pipe that changes nothing, which is where a chain of operators starts. */
fun <A : Any> Pipe.Companion.identity(): Pipe<Nothing, A, A> = Pipe(Node.Hole)

/** [next]'s failure joins this one's: a narrower `E2` slots in, as `absolve`'s `L` does. */
fun <E, E2 : E, In, Out : Any, Out2 : Any> Pipe<E, In, Out>.via(next: Pipe<E2, Out, Out2>): Pipe<E, In, Out2> =
    Pipe(next.node.spliced(node))

/** The pipe's elements become the stream's, and the failure it declares joins the stream's. */
fun <E, E2 : E, A : Any, B : Any> Stream<E, A>.via(pipe: Pipe<E2, A, B>): Stream<E, B> = Stream(pipe.node.spliced(node))

/**
 * A pipe spliced onto a source, answering with the pipe's own failure rather than the stream's.
 *
 * `either`, `catchAll`, `mapError` and `orElse` replace what the stream declared instead of adding to it, so `E2 : E`
 * cannot type them. The failure is read off [pipe] rather than picked by the caller, which is what stops a fifth
 * operator naming a type nothing here produces.
 */
internal fun <E2, A : Any, B : Any> Stream<*, A>.replacing(pipe: Pipe<E2, A, B>): Stream<E2, B> =
    Stream(pipe.node.spliced(node))

/** As above, for a pipe spliced onto a pipe. */
internal fun <E2, In, Out : Any, Out2 : Any> Pipe<*, In, Out>.replacing(
    next: Pipe<E2, Out, Out2>,
): Pipe<E2, In, Out2> = Pipe(next.node.spliced(node))

fun <A : Any, B : Any> Pipe.Companion.map(f: (A) -> B): Pipe<Nothing, A, B> =
    Pipe(Node.Map(Node.Hole, f.erased(), buildSite()))

fun <E, In, Out : Any, Out2 : Any> Pipe<E, In, Out>.map(f: (Out) -> Out2): Pipe<E, In, Out2> = via(Pipe.map(f))

/**
 * The starting form the failure type is read out of: `Pipe.mapOrFail<E, A, B> { }` is the pipe twin of
 * `Stream.from(rows).mapOrFail { }`.
 */
fun <E, A : Any, B : Any> Pipe.Companion.mapOrFail(f: Failing<E>.(A) -> B): Pipe<E, A, B> =
    Pipe(Node.MapOrFail(Node.Hole, f.erased(), buildSite()))

/**
 * As above, on a pipe that has yet to name a failure: this one reads the failure out of the body.
 *
 * The pair is `Stream.mapOrFail`'s, for its reason: Kotlin fixes the failure type from the receiver before it looks at
 * the `fail(e)` naming one. The more specific receiver decides, so a call site never picks between them.
 */
// The two erase to one JVM signature, so one of them needs a name of its own down there. Kotlin
// callers never see it.
@JvmName("mapOrFailDeclaring")
fun <F, In, Out : Any, Out2 : Any> Pipe<Nothing, In, Out>.mapOrFail(f: Failing<F>.(Out) -> Out2): Pipe<F, In, Out2> =
    via(Pipe.mapOrFail(f))

/** As above, for a pipe whose failure type is already named. */
fun <E, In, Out : Any, Out2 : Any> Pipe<E, In, Out>.mapOrFail(f: Failing<E>.(Out) -> Out2): Pipe<E, In, Out2> =
    via(Pipe.mapOrFail(f))

fun <A : Any> Pipe.Companion.filter(predicate: (A) -> Boolean): Pipe<Nothing, A, A> =
    Pipe(Node.Filter(Node.Hole, predicate.erased(), buildSite()))

fun <E, In, Out : Any> Pipe<E, In, Out>.filter(predicate: (Out) -> Boolean): Pipe<E, In, Out> =
    via(Pipe.filter(predicate))
