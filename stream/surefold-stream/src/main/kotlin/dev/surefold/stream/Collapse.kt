package dev.surefold.stream

/**
 * What a backend compiles: the tree [collapsed], then [fused]. Each rewrite is a pattern that moves nothing a caller
 * can observe, so a pipeline answers the same with or without it.
 */
@StreamSpi fun Node.optimised(): Node = collapsed().fused()

/**
 * The tree with the rules below applied bottom-up. A node none of them touches, with nothing under it touched, comes
 * back as the same instance, so a tree with nothing to collapse is the tree it was.
 *
 * - `take(a).take(b)` is `take(min(a, b))`, and `drop(a).drop(b)` is `drop(a + b)`.
 * - A `catchAll`, `orElse` or `mapError` over a subtree that cannot fail is removed: it would never run.
 */
@StreamSpi
fun Node.collapsed(): Node =
    when (this) {
        is Node.Unary -> upstream.collapsed().let { up -> if (up === upstream) this else on(up) }.rule()

        is Node.Merge -> rebuilt(upstream, other) { a, b -> Node.Merge(a, b) }

        is Node.Interleave -> rebuilt(upstream, other) { a, b -> Node.Interleave(a, b, segmentSize) }

        is Node.ZipWith -> rebuilt(upstream, other) { a, b -> Node.ZipWith(a, b, f, at) }

        is Node.Prepend -> rebuilt(upstream, first) { a, b -> Node.Prepend(a, b) }

        is Node.Concat -> rebuilt(upstream, next) { a, b -> Node.Concat(a, b) }

        is Node.RestartOnDefect ->
            upstream.collapsed().let { up ->
                if (up === upstream) this else Node.RestartOnDefect(up, step, logger, clock)
            }

        is Node.Native,
        is Node.Elements,
        is Node.Single,
        is Node.Tick,
        is Node.FromStage,
        is Node.Fail,
        is Node.Blocking,
        Node.Empty,
        Node.Hole -> this
    }

private fun Node.rebuilt(left: Node, right: Node, build: (Node, Node) -> Node): Node {
    val a = left.collapsed()
    val b = right.collapsed()
    return if (a === left && b === right) this else build(a, b)
}

private fun Node.rule(): Node {
    val up = (this as? Node.Unary)?.upstream
    return when {
        this is Node.Take && up is Node.Take -> Node.Take(up.upstream, minOf(n, up.n))
        this is Node.Drop && up is Node.Drop -> Node.Drop(up.upstream, saturated(n, up.n))
        this is Node.CatchAll && up != null && !up.canFail() -> up
        this is Node.MapError && up != null && !up.canFail() -> up
        else -> this
    }
}

private fun saturated(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

/**
 * Whether a declared failure can come out of this subtree, read off its shape alone. Where the shape cannot say, it
 * answers yes: a pipe's input, a recovery built only when a failure arrives, an inner stream. A value a caller handed
 * over cannot: its type says `Nothing`, and what it throws is a defect.
 */
@StreamSpi
fun Node.canFail(): Boolean =
    when (this) {
        is Node.Elements,
        is Node.Single,
        is Node.Tick,
        Node.Empty,
        is Node.Native,
        is Node.Either,
        is Node.Blocking -> false

        is Node.Fail,
        is Node.FromStage,
        Node.Hole,
        is Node.MapOrFail,
        is Node.MapPar,
        is Node.Absolve,
        is Node.OrFailIfEmpty,
        is Node.CatchAll,
        is Node.FlatMap -> true

        is Node.Fused -> steps.any { it is Step.MapOrFail } || upstream.canFail()

        is Node.Merge,
        is Node.Interleave,
        is Node.ZipWith,
        is Node.Prepend,
        is Node.Concat,
        is Node.RestartOnDefect,
        is Node.Stage,
        is Node.Map,
        is Node.Filter,
        is Node.FilterNot,
        is Node.Take,
        is Node.Drop,
        is Node.TakeWhile,
        is Node.DropWhile,
        is Node.Grouped,
        is Node.Sliding,
        is Node.Buffer,
        is Node.GroupedWithin,
        is Node.Scan,
        is Node.StatefulMap,
        is Node.MapConcat,
        is Node.MapAsync,
        is Node.Conflate,
        is Node.MapError,
        is Node.Probed -> children().any { it.canFail() }
    }
