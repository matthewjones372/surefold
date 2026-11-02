package io.github.matthewjones372.surefold.stream

import dev.surefold.Clock
import dev.surefold.Logger
import dev.surefold.ScheduleStep
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import kotlin.time.Duration

/**
 * A stream as data: what the operators build and what a backend compiles.
 *
 * Untyped inside, because the element and failure types are [Stream]'s to carry and a node is only
 * ever reached through the stream that typed it. Nothing here names a backend's types: a value only one
 * backend can run is held opaquely, with the [BackendKey] of the backend that can.
 */
@StreamSpi
sealed interface Node {

    /** A source only [owner] can run, such as a Pekko `Source` a caller handed over. */
    class Native(
        val value: Any,
        override val owner: BackendKey,
        override val builder: String,
        override val at: String,
    ) : StreamOnly, Owned

    class Elements(val elements: Iterable<Any>) : StreamOnly

    class Single(val element: Any) : StreamOnly

    class Tick(val every: Duration, val element: Any, val after: Duration) : StreamOnly

    class FromStage(val stage: CompletionStage<*>, val onNull: () -> Throwable) : StreamOnly

    /** A resource opened per run and read by blocking: [Stream.Companion.blocking]'s functions, erased. */
    class Blocking(
        val open: () -> Any,
        val next: (Any) -> Any?,
        val wake: (Any) -> Unit,
        val close: (Any) -> Unit,
        val at: String,
        val ready: (Any) -> Boolean = { false },
    ) : StreamOnly

    class Fail(val error: Any?) : StreamOnly

    data object Empty : StreamOnly

    /** A node no pipe can hold: a source, or an operator that reads more than the one stream it is on. */
    sealed interface StreamOnly : Node

    /** Pekko's `merge`: both, in whatever order they arrive. */
    class Merge(val upstream: Node, val other: Node) : StreamOnly

    class Interleave(val upstream: Node, val other: Node, val segmentSize: Int) : StreamOnly

    class ZipWith(val upstream: Node, val other: Node, val f: (Any, Any) -> Any, val at: String) : StreamOnly

    /** [first]'s elements, then [upstream]'s: Pekko's `prepend`, which is not quite `first.concat`. */
    class Prepend(val upstream: Node, val first: Node) : StreamOnly

    class Concat(val upstream: Node, val next: Node) : StreamOnly

    /** [upstream] materialised again on a defect, as [step] decides: the whole stream, never one stage. */
    // The logger and clock are the ones in scope where the stream was built: a restart is decided on a
    // backend's thread, which inherits neither.
    class RestartOnDefect(
        val upstream: Node,
        val step: ScheduleStep<Throwable, *>,
        val logger: Logger,
        val clock: Clock,
    ) : StreamOnly

    /** A pipe's input: the place a stream goes when the pipe is spliced onto it. */
    data object Hole : Node

    /** One input, one output: every operator a [Pipe] can hold, and the ones [spliced] rebuilds. */
    sealed interface Unary : Node {
        val upstream: Node

        /** This operator, reading from [upstream] instead. */
        fun on(upstream: Node): Unary
    }

    /** A stage only [owner] can run, such as a Pekko `Flow` a caller handed over. */
    data class Stage(
        override val upstream: Node,
        val value: Any,
        override val owner: BackendKey,
        override val builder: String,
        override val at: String,
    ) : Unary, Owned {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    /** [at] is read where the operator was written, which is the only time the caller's frame is there to read. */
    data class Map(override val upstream: Node, val f: (Any) -> Any, val at: String) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class MapOrFail(override val upstream: Node, val f: Failing<Any?>.(Any) -> Any, val at: String) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class Filter(override val upstream: Node, val predicate: (Any) -> Boolean, val at: String) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }
    data class FilterNot(override val upstream: Node, val predicate: (Any) -> Boolean, val at: String) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class Take(override val upstream: Node, val n: Long) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class Drop(override val upstream: Node, val n: Long) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class TakeWhile(override val upstream: Node, val predicate: (Any) -> Boolean, val at: String) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class DropWhile(override val upstream: Node, val predicate: (Any) -> Boolean, val at: String) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class Grouped(override val upstream: Node, val n: Int) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class Sliding(override val upstream: Node, val n: Int, val step: Int) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    /** Everything upstream runs ahead of what reads it by up to [size] elements, and no further. */
    data class Buffer(override val upstream: Node, val size: Int) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class GroupedWithin(override val upstream: Node, val n: Int, val within: Duration) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class Scan(override val upstream: Node, val zero: Any, val f: (Any, Any) -> Any, val at: String) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class MapConcat(override val upstream: Node, val f: (Any) -> Iterable<Any>, val at: String) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class MapAsync(
        override val upstream: Node,
        val parallelism: Int,
        val f: (Any) -> CompletionStage<Any>,
        val at: String,
        /** False passes each answer on as it completes (spec 0170); a backend may still keep order. */
        val ordered: Boolean = true,
    ) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class Conflate(
        override val upstream: Node,
        val seed: (Any) -> Any,
        val aggregate: (Any, Any) -> Any,
        val at: String,
    ) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    /** The declared failure as a last element, `Left`; every element before it a `Right`. */
    data class Either(override val upstream: Node) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class Absolve(override val upstream: Node, val at: String) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    /** [f] answers with the node of the stream that takes over, so the recovery is described too. */
    data class CatchAll(override val upstream: Node, val f: (Any?) -> Node) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class MapError(override val upstream: Node, val f: (Any?) -> Any?, val at: String) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class OrFailIfEmpty(override val upstream: Node, val error: Any?) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    /**
     * [f] answers with a [Stream] rather than its node, so the compiler can reuse the source a stream
     * built once already has: `flatten` over streams described ahead of time compiles each of them once.
     */
    data class FlatMap(
        override val upstream: Node,
        val f: (Any) -> Stream<*, Any>,
        val breadth: Int?,
        val at: String,
    ) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class MapPar(
        override val upstream: Node,
        val parallelism: Int,
        val on: Executor,
        val f: dev.surefold.fail.Fail<Any?>.(Any) -> Any,
        val at: String,
        /** Null keeps the input's order; otherwise only elements with the same key keep theirs. */
        val key: ((Any) -> Any)? = null,
        /** False passes each answer on as it completes (spec 0170); a backend may still keep order. */
        val ordered: Boolean = true,
    ) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    /** Adjacent element-at-a-time operators merged by [fused]: one stage a backend runs, [steps] in order. */
    data class Fused(override val upstream: Node, val steps: List<Step>) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    /** Watches what [upstream] emits and when it is next asked for more: where a measured run counts and waits. */
    data class Probed(override val upstream: Node, val probe: Probe) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    /** `S` may be nullable, which is why the state is `Any?` where every element is `Any`. */
    data class StatefulMap(
        override val upstream: Node,
        val create: () -> Any?,
        val f: (Any?, Any) -> Pair<Any?, Any>,
        val onComplete: (Any?) -> Any?,
        val at: String,
    ) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }
}

/**
 * A pipe's tree with its [Node.Hole] replaced by [onto]: splicing rebuilds the pipe's own nodes and
 * shares everything upstream, so a pipe reused across streams is copied once per splice.
 */
internal fun Node.spliced(onto: Node): Node =
    when (this) {
        Node.Hole -> onto
        is Node.Unary -> on(upstream.spliced(onto))
        is Node.StreamOnly -> error("a pipe holds single-input operators only, and reached $this")
    }

/** Where a run's elements go, and what it completes with. */
@StreamSpi
sealed interface End {

    data object Collect : End

    /** [at] is where the fold was written: a body written in Java can still answer with null. */
    class Fold(val zero: Any, val f: (Any, Any) -> Any, val at: String) : End

    /** A sink only [owner] can run, such as a Pekko `Sink` a caller handed over. */
    class Native(
        val value: Any,
        override val owner: BackendKey,
        override val builder: String,
        override val at: String,
    ) : End, Owned
}

/** A value only one backend can run, and what a refusal says about it: the builder and the caller's line. */
@StreamSpi
interface Owned {
    val owner: BackendKey
    val builder: String
    val at: String
}

/** The operator a node is, by the name a caller wrote: what a refusal, a rendering or a metric calls it. */
@StreamSpi
val Node.operator: String
    get() = when (this) {
        is Node.Native -> builder

        is Node.Stage -> builder

        is Node.Elements -> "Stream.from"

        is Node.Single -> "Stream.single"

        is Node.Tick -> "Stream.tick"

        is Node.FromStage -> "Stream.fromStage"

        is Node.Blocking -> "Stream.blocking"

        is Node.Fail -> "Stream.fail"

        Node.Empty -> "Stream.empty"

        Node.Hole -> "Pipe.identity"

        is Node.Merge -> "merge"

        is Node.Interleave -> "interleave"

        is Node.ZipWith -> "zipWith"

        is Node.Prepend -> "prepend"

        is Node.Concat -> "concat"

        is Node.RestartOnDefect -> "restartOnDefect"

        is Node.FlatMap -> if (breadth == null) "flatMapConcat" else "flatMapMerge"

        is Node.Conflate -> "conflateWithSeed"

        is Node.Fused -> steps.joinToString(prefix = "fused[", postfix = "]") { it.operator }

        is Node.Probed -> "probe"

        is Node.Map, is Node.MapOrFail, is Node.Filter, is Node.FilterNot, is Node.Take, is Node.Drop,
        is Node.TakeWhile, is Node.DropWhile, is Node.Grouped, is Node.Sliding, is Node.GroupedWithin,
        is Node.Scan, is Node.StatefulMap, is Node.MapConcat, is Node.MapAsync, is Node.Either, is Node.Absolve,
        is Node.CatchAll, is Node.MapError, is Node.OrFailIfEmpty, is Node.MapPar, is Node.Buffer,
        -> javaClass.simpleName.replaceFirstChar { it.lowercase() }
    }

/** The caller's line that wrote a node, for the operators that run caller code and so read one. */
@StreamSpi
val Node.site: String?
    get() = when (this) {
        is Owned -> at

        is Node.Map -> at

        is Node.MapOrFail -> at

        is Node.Filter -> at

        is Node.FilterNot -> at

        is Node.TakeWhile -> at

        is Node.DropWhile -> at

        is Node.Scan -> at

        is Node.StatefulMap -> at

        is Node.MapConcat -> at

        is Node.MapAsync -> at

        is Node.Conflate -> at

        is Node.Absolve -> at

        is Node.MapError -> at

        is Node.Blocking -> at

        is Node.FlatMap -> at

        is Node.MapPar -> at

        is Node.ZipWith -> at

        is Node.Fused -> steps.first().at

        is Node.Elements, is Node.Single, is Node.Tick, is Node.FromStage, is Node.Fail, Node.Empty, Node.Hole,
        is Node.Merge, is Node.Interleave, is Node.Prepend, is Node.Concat, is Node.RestartOnDefect,
        is Node.Take, is Node.Drop, is Node.Grouped, is Node.Sliding, is Node.GroupedWithin, is Node.Either,
        is Node.CatchAll, is Node.OrFailIfEmpty, is Node.Probed, is Node.Buffer,
        -> null
    }

/** This node reading from its children as [transform] answers them, and itself where none of them changed. */
@StreamSpi
fun Node.withChildren(transform: (Node) -> Node): Node =
    when (this) {
        is Node.Unary -> transform(upstream).let { up -> if (up === upstream) this else on(up) }

        is Node.Merge -> pair(upstream, other, transform) { a, b -> Node.Merge(a, b) }

        is Node.Interleave -> pair(upstream, other, transform) { a, b -> Node.Interleave(a, b, segmentSize) }

        is Node.ZipWith -> pair(upstream, other, transform) { a, b -> Node.ZipWith(a, b, f, at) }

        is Node.Prepend -> pair(upstream, first, transform) { a, b -> Node.Prepend(a, b) }

        is Node.Concat -> pair(upstream, next, transform) { a, b -> Node.Concat(a, b) }

        is Node.RestartOnDefect ->
            transform(upstream).let { up ->
                if (up ===
                    upstream
                ) this else Node.RestartOnDefect(up, step, logger, clock)
            }

        is Node.Native, is Node.Elements, is Node.Single, is Node.Tick, is Node.FromStage, is Node.Fail,
        is Node.Blocking, Node.Empty, Node.Hole,
        -> this
    }

private fun Node.pair(left: Node, right: Node, transform: (Node) -> Node, build: (Node, Node) -> Node): Node {
    val a = transform(left)
    val b = transform(right)
    return if (a === left && b === right) this else build(a, b)
}

/** The nodes this one reads from, in the order its elements come from them. */
@StreamSpi
fun Node.children(): List<Node> =
    when (this) {
        is Node.Unary -> listOf(upstream)

        is Node.Merge -> listOf(upstream, other)

        is Node.Interleave -> listOf(upstream, other)

        is Node.ZipWith -> listOf(upstream, other)

        is Node.Prepend -> listOf(first, upstream)

        is Node.Concat -> listOf(upstream, next)

        is Node.RestartOnDefect -> listOf(upstream)

        is Node.Native, is Node.Elements, is Node.Single, is Node.Tick, is Node.FromStage, is Node.Fail,
        is Node.Blocking, Node.Empty, Node.Hole,
        -> emptyList()
    }

/** A typed caller function as a node holds it. Erasure makes this a no-op; the stream's types say what comes out. */
@Suppress("UNCHECKED_CAST")
internal fun <F> Function<*>.erased(): F = this as F
