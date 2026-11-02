package dev.surefold.stream

/**
 * One step of a [Node.Fused] run: an operator that answers one element, or none, for each element in.
 *
 * Each keeps its operator's name and the caller's line, so a defect in a fused run still names the step
 * that threw, the element it was handed, and where it was written.
 */
@StreamSpi
sealed interface Step {
    val at: String

    class Map(val f: (Any) -> Any, override val at: String) : Step

    class MapOrFail(val f: Failing<Any?>.(Any) -> Any, override val at: String) : Step

    /** [keep] is what the predicate has to answer for an element to go on: true for `filter`, false for `filterNot`. */
    class Filter(val predicate: (Any) -> Boolean, val keep: Boolean, override val at: String) : Step
}

/**
 * A step as a backend's loop calls it: the next value, or `null` where a filter dropped the element. Each
 * body has the guard the unfused operator had, so a defect names the same operator, element and line.
 */
@StreamSpi
fun Step.body(): (Any) -> Any? =
    when (this) {
        is Step.Map -> guarded(operator, at, f)

        is Step.MapOrFail -> {
            val scope = Failing<Any?>()
            guarded(operator, at) { a: Any -> scope.f(a) }
        }

        is Step.Filter -> {
            val test = guarded(operator, at, predicate)
            val kept: (Any) -> Any? = { a -> a.takeIf { test(it) == keep } }
            kept
        }
    }

/**
 * [value] through the step bodies from [from] on, stopping at the first that drops it. `tailrec`, so it
 * compiles to the loop it reads as: a fused stage calls it once per element, and must not allocate.
 */
@StreamSpi
tailrec fun Array<(Any) -> Any?>.through(value: Any?, from: Int = 0): Any? =
    if (value == null || from == size) value else through(this[from](value), from + 1)

/** The name a caller wrote for a step, as the unfused node would have been named. */
@StreamSpi
val Step.operator: String
    get() = when (this) {
        is Step.Map -> "map"
        is Step.MapOrFail -> "mapOrFail"
        is Step.Filter -> if (keep) "filter" else "filterNot"
    }

/**
 * The tree with every run of two or more adjacent element-at-a-time operators merged into one
 * [Node.Fused], which a backend runs as one stage. Nothing observable moves: the steps run in order, on
 * each element, with the guards they had. A backend applies it when it compiles.
 */
@StreamSpi
fun Node.fused(): Node =
    when (this) {
        is Node.Unary -> {
            val steps = stepsEndingHere()
            if (steps.size < 2) on(upstream.fused()) else Node.Fused(steps.base.fused(), steps.steps)
        }

        is Node.Merge -> Node.Merge(upstream.fused(), other.fused())

        is Node.Interleave -> Node.Interleave(upstream.fused(), other.fused(), segmentSize)

        is Node.ZipWith -> Node.ZipWith(upstream.fused(), other.fused(), f, at)

        is Node.Prepend -> Node.Prepend(upstream.fused(), first.fused())

        is Node.Concat -> Node.Concat(upstream.fused(), next.fused())

        is Node.RestartOnDefect -> Node.RestartOnDefect(upstream.fused(), step, logger, clock)

        is Node.Native, is Node.Elements, is Node.Single, is Node.Tick, is Node.FromStage, is Node.Fail,
        is Node.Blocking,
        Node.Empty, Node.Hole,
        -> this
    }

/** The steps that end at this node, upstream first, and the node the first of them reads from. */
private class Steps(val steps: List<Step>, val base: Node) {
    val size get() = steps.size
}

private fun Node.Unary.stepsEndingHere(): Steps {
    val chain = generateSequence<Node.Unary>(this) { it.upstream as? Node.Unary }
        .takeWhile { it.asStep() != null }
        .toList()
    return Steps(chain.mapNotNull { it.asStep() }.asReversed(), chain.lastOrNull()?.upstream ?: this)
}

private fun Node.Unary.asStep(): Step? =
    when (this) {
        is Node.Map -> Step.Map(f, at)

        is Node.MapOrFail -> Step.MapOrFail(f, at)

        is Node.Filter -> Step.Filter(predicate, keep = true, at)

        is Node.FilterNot -> Step.Filter(predicate, keep = false, at)

        is Node.Stage, is Node.Take, is Node.Drop, is Node.TakeWhile, is Node.DropWhile, is Node.Grouped,
        is Node.Sliding, is Node.Buffer, is Node.GroupedWithin, is Node.Scan, is Node.StatefulMap, is Node.MapConcat,
        is Node.MapAsync, is Node.Conflate, is Node.Either, is Node.Absolve, is Node.CatchAll, is Node.MapError,
        is Node.OrFailIfEmpty, is Node.FlatMap, is Node.MapPar, is Node.Fused, is Node.Probed,
        -> null
    }
