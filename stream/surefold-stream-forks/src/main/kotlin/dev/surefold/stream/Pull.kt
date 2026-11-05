package dev.surefold.stream

import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.fold
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException

/**
 * One element at a time, on the thread that asks. `null` is the end, which no element can be, because every element is
 * `Any`. A stage only runs when the one after it asks, so a `take` stops its source.
 */
@StreamSpi
fun interface Pull {
    fun next(): Any?
}

/**
 * Where a backend built on the pull loop runs the operators Forks forks for, its own way: handed to [Pulling], and
 * asked for each node as its pull is built. [pull] answers null for a node it leaves to Forks, and builds the pull of
 * any node under it with [pulled].
 */
@StreamSpi
fun interface Boundaries {
    fun pull(node: Node, pulled: (Node) -> Pull): Pull?
}

/**
 * What a pull is built under, handed to it rather than read from its thread (spec 0152): the run's time, its forks and
 * blocking sources, and the backend that runs some operators its own way. A pull built later, by a `concat`, a
 * `catchAll` or a fork, is built under the one it was handed.
 */
internal class PullContext(
    /** The run's turns on a test's clock, or none on Forks. */
    val turns: Turns?,
    val releases: Releases?,
    val resources: Resources?,
    val boundaries: Boundaries?,
) {
    val onClock: Boolean
        get() = turns != null

    /** The turns of a run on a test's clock, for a node that only pulls on one. */
    val clockTurns: Turns
        get() = checkNotNull(turns) { "a node with time in it was pulled off a test's clock" }

    /** The same run, with [scope] for the blocking sources the pulls built under it open: one restart's attempt. */
    fun within(scope: Resources): PullContext = PullContext(turns, releases, scope, boundaries)

    /** The run's time: a test's clock, or the run's own clock on Forks. */
    fun timeline(): Timeline =
        turns ?: RealTime(checkNotNull(releases) { "a node with time in it was pulled outside a run" })
}

/** The operators a pull runs: every one with no backend of its own in it, since spec 0052. */
internal fun Node.pulls(): Boolean =
    when (this) {
        is Node.Elements,
        is Node.Single,
        Node.Empty,
        is Node.Fail,
        is Node.FromStage,
        is Node.Blocking,
        is Node.Map,
        is Node.MapOrFail,
        is Node.Filter,
        is Node.FilterNot,
        is Node.Take,
        is Node.Drop,
        is Node.TakeWhile,
        is Node.DropWhile,
        is Node.Grouped,
        is Node.Scan,
        is Node.StatefulMap,
        is Node.MapConcat,
        is Node.Either,
        is Node.Absolve,
        is Node.CatchAll,
        is Node.MapError,
        is Node.OrFailIfEmpty,
        is Node.Concat,
        is Node.Prepend,
        is Node.ZipWith,
        is Node.Fused,
        is Node.Probed,
        is Node.MapPar,
        is Node.Buffer,
        is Node.Sliding,
        is Node.Interleave,
        is Node.MapAsync,
        is Node.FlatMap,
        is Node.Merge,
        is Node.Conflate,
        is Node.Tick,
        is Node.GroupedWithin,
        is Node.RestartOnDefect -> true

        is Node.Native,
        is Node.Stage,
        Node.Hole -> false
    }

/**
 * The pull a node describes. `start` has refused every node its backend cannot run before this is reached. A node with
 * time in it waits on the run's [Timeline]: a test's clock, or the run's own on Forks.
 */
context(ctx: PullContext)
internal fun Node.pull(): Pull =
    ctx.boundaries?.takeUnless { ctx.onClock }?.pull(this) { it.pull() }
        ?: when (this) {
            is Node.Elements -> elements.iterator().let { items -> Pull { items.takeIf { it.hasNext() }?.next() } }

            is Node.Single -> once { element }

            Node.Empty -> Pull { null }

            is Node.Fail -> Pull { throw DeclaredFailure(error) }

            is Node.FromStage -> once { awaited(this) }

            is Node.Blocking -> blocking(ctx.resources)

            is Node.Map -> map()

            is Node.MapOrFail -> mapOrFail()

            is Node.Filter -> kept("filter", at) { a -> predicate(a) }

            is Node.FilterNot -> kept("filterNot", at) { a -> !predicate(a) }

            is Node.Take -> take()

            is Node.Drop -> drop()

            is Node.TakeWhile -> takeWhile()

            is Node.DropWhile -> dropWhile()

            is Node.Grouped -> grouped()

            is Node.Scan -> scan()

            is Node.StatefulMap -> statefulMap()

            is Node.MapConcat -> mapConcat()

            is Node.Either -> either()

            is Node.Absolve -> absolve()

            is Node.CatchAll -> catchAll()

            is Node.MapError -> mapError()

            is Node.OrFailIfEmpty -> orFailIfEmpty()

            is Node.FlatMap -> flatMapped(breadth)

            is Node.Concat -> upstream.then(next)

            is Node.Prepend -> first.then(upstream)

            is Node.ZipWith -> zipWith()

            is Node.Fused -> fusedLoop()

            is Node.Probed -> probed()

            is Node.Tick -> tick(ctx.timeline())

            is Node.GroupedWithin -> groupedWithin(ctx.timeline())

            is Node.RestartOnDefect -> restarting(ctx.timeline())

            // On a test's clock one element at a time, in the order they came, which is the answer a test of
            // timing wants, and keeps every key's order too; on Forks, a window of bodies in flight.
            is Node.MapPar -> {
                val keyed = key
                when {
                    ctx.onClock -> inOrder()
                    keyed != null -> keyedWindow(keyed)
                    ordered -> window()
                    else -> unorderedWindow()
                }
            }

            is Node.Buffer -> if (ctx.onClock) bufferedOnClock(ctx.clockTurns) else buffered()

            is Node.Sliding -> sliding()

            is Node.Interleave -> interleave()

            // On a test's clock one stage at a time, as mapPar is; on Forks, a window of them fed by a fork.
            is Node.MapAsync ->
                when {
                    ctx.onClock -> awaiting(1)
                    ordered -> fed(parallelism)
                    else -> fedUnordered()
                }

            is Node.Merge -> if (ctx.onClock) mergedOnClock(ctx.clockTurns) else merged()

            is Node.Conflate -> if (ctx.onClock) conflatedOnClock(ctx.clockTurns) else conflated()

            is Node.Native,
            is Node.Stage,
            Node.Hole -> error("$operator reached the Forks runner, which start refuses it before")
        }

private fun once(element: () -> Any): Pull {
    var given = false
    return Pull {
        if (given) {
            null
        } else {
            given = true
            element()
        }
    }
}

/**
 * Opened on the first pull rather than when the loop is built, so a stream nobody pulls opens nothing, and closed as
 * soon as it runs out; the run closes it otherwise, once its loop has ended.
 */
@Suppress("TooGenericExceptionCaught") // Only to close before the failure goes on, unchanged.
context(ctx: PullContext)
private fun Node.Blocking.blocking(resources: Resources?): Pull {
    var opened: Opened? = null
    var done = false
    return Pull {
        if (done) return@Pull null
        val resource =
            opened
                ?: Opened(this).also {
                    opened = it
                    resources?.add(it)
                }
        // A read that failed closes its resource at once: a restart opens a new one, and must not find the
        // old one still holding what it held, a consumer's place in its group among them.
        val next =
            try {
                resource.next()
            } catch (failed: Throwable) {
                done = true
                resource.close()
                throw failed
            }
        next
            ?: null.also {
                done = true
                resource.close()
            }
    }
}

/** A stage's value on the pulling thread, with what failed it rather than the wrapper the JDK adds. */
@Suppress("UNCHECKED_CAST")
private fun awaited(from: Node.FromStage): Any =
    try {
        (from.stage as CompletionStage<Any>).checked(from.onNull).toCompletableFuture().join()
    } catch (wrapped: CompletionException) {
        throw wrapped.cause ?: wrapped
    }

context(ctx: PullContext)
private fun Node.Map.map(): Pull {
    val body = guarded("map", at, f)
    val up = upstream.pull()
    return Pull { up.next()?.let(body) }
}

context(ctx: PullContext)
private fun Node.MapOrFail.mapOrFail(): Pull {
    val scope = Failing<Any?>()
    val body = guarded("mapOrFail", at) { a: Any -> scope.f(a) }
    val up = upstream.pull()
    return Pull { up.next()?.let(body) }
}

/** A `mapPar` one element at a time, on the pulling thread: its raise is the stream's, as on Pekko. */
context(ctx: PullContext)
private fun Node.MapPar.inOrder(): Pull {
    val body = guarded("mapPar", at) { a: Any -> attempt { f(a) } }
    val up = upstream.pull()
    return Pull { up.next()?.let { a -> body(a).fold({ e -> throw DeclaredFailure(e) }, { b -> b }) } }
}

context(ctx: PullContext)
private fun Node.Unary.kept(operator: String, at: String, test: (Any) -> Boolean): Pull {
    val guard = guarded(operator, at, test)
    val up = upstream.pull()
    return Pull {
        var a = up.next()
        while (a != null && !guard(a)) a = up.next()
        a
    }
}

context(ctx: PullContext)
private fun Node.Take.take(): Pull {
    val up = upstream.pull()
    var left = n
    return Pull {
        if (left <= 0) {
            null
        } else {
            left--
            up.next()
        }
    }
}

context(ctx: PullContext)
private fun Node.Drop.drop(): Pull {
    val up = upstream.pull()
    var skip = n
    return Pull {
        while (skip > 0) {
            skip--
            if (up.next() == null) return@Pull null
        }
        up.next()
    }
}

context(ctx: PullContext)
private fun Node.TakeWhile.takeWhile(): Pull {
    val test = guarded("takeWhile", at, predicate)
    val up = upstream.pull()
    var open = true
    return Pull {
        val a = if (open) up.next() else null
        if (a != null && !test(a)) open = false
        a.takeIf { open }
    }
}

context(ctx: PullContext)
private fun Node.DropWhile.dropWhile(): Pull {
    val test = guarded("dropWhile", at, predicate)
    val up = upstream.pull()
    var dropping = true
    return Pull {
        var a = up.next()
        while (dropping && a != null && test(a)) a = up.next()
        dropping = false
        a
    }
}

context(ctx: PullContext)
private fun Node.Grouped.grouped(): Pull {
    val up = upstream.pull()
    return Pull {
        buildList {
            while (size < n) add(up.next() ?: break)
        }
            .ifEmpty { null }
    }
}

context(ctx: PullContext)
private fun Node.Scan.scan(): Pull {
    val carry = guarded("scan", at, f)
    val up = upstream.pull()
    var carried: Any? = null
    return Pull {
        val last = carried
        if (last == null) {
            zero.also { carried = it }
        } else {
            up.next()?.let { a -> carry(last, a).also { carried = it } }
        }
    }
}

context(ctx: PullContext)
private fun Node.StatefulMap.statefulMap(): Pull {
    val step = guarded("statefulMap", at, f)
    val up = upstream.pull()
    var state = create()
    var ended = false
    return Pull {
        if (ended) return@Pull null
        val a = up.next()
        if (a == null) {
            ended = true
            onComplete(state)
        } else {
            val (next, out) = step(state, a)
            state = next
            out
        }
    }
}

context(ctx: PullContext)
private fun Node.MapConcat.mapConcat(): Pull {
    val body = guarded("mapConcat", at, f)
    val up = upstream.pull()
    var current: Iterator<Any> = emptyList<Any>().iterator()
    return Pull {
        while (!current.hasNext()) current = body(up.next() ?: return@Pull null).iterator()
        current.next()
    }
}

/** The declared failure as a last `Left`: a pull that failed is not pulled again, so the stream ends there. */
context(ctx: PullContext)
private fun Node.Either.either(): Pull {
    val up = upstream.pull()
    var ended = false
    return Pull {
        if (ended) return@Pull null
        try {
            up.next()?.let { a -> Either.Right(a) }
        } catch (failure: DeclaredFailure) {
            ended = true
            Either.Left(failure.declared<Any?>())
        }
    }
}

@Suppress("UNCHECKED_CAST")
context(ctx: PullContext)
private fun Node.Absolve.absolve(): Pull {
    val decided =
        guarded("absolve", at) { either: Any ->
            (either as Either<Any?, Any>).fold({ left -> throw DeclaredFailure(left) }, { right -> right })
        }
    val up = upstream.pull()
    return Pull { up.next()?.let(decided) }
}

/** One recovery, as Pekko's `recoverWithRetries(1)`: a failure of the stream that took over ends the run. */
context(ctx: PullContext)
private fun Node.CatchAll.catchAll(): Pull {
    var current = upstream.pull()
    var recovered = false
    return Pull {
        try {
            current.next()
        } catch (failure: DeclaredFailure) {
            if (recovered) throw failure
            recovered = true
            current = f(failure.declared()).optimised().pull()
            current.next()
        }
    }
}

// A declared failure carries a value and no stack, so the one replaced is not lost: its value is mapped.
@Suppress("SwallowedException")
context(ctx: PullContext)
private fun Node.MapError.mapError(): Pull {
    val mapped = guardedError("mapError", at, f)
    val up = upstream.pull()
    return Pull {
        try {
            up.next()
        } catch (failure: DeclaredFailure) {
            throw DeclaredFailure(mapped(failure.declared()))
        }
    }
}

context(ctx: PullContext)
private fun Node.OrFailIfEmpty.orFailIfEmpty(): Pull {
    val up = upstream.pull()
    var emitted = false
    return Pull {
        val a = up.next()
        if (a == null && !emitted) throw DeclaredFailure(error)
        emitted = true
        a
    }
}

/** One inner stream after another, or up to [breadth] at once: on a test's clock as workers taking turns. */
context(ctx: PullContext)
private fun Node.FlatMap.flatMapped(breadth: Int?): Pull =
    when {
        breadth == null -> flatMapConcat()
        ctx.onClock -> mergedOnClock(breadth, ctx.clockTurns)
        else -> merged(breadth)
    }

context(ctx: PullContext)
private fun Node.FlatMap.flatMapConcat(): Pull {
    val build = guarded("flatMapConcat", at, f)
    val up = upstream.pull()
    var inner: Pull? = null
    return Pull {
        var element = inner?.next()
        while (element == null) {
            val started = build(up.next() ?: return@Pull null).node.optimised().pull()
            inner = started
            element = started.next()
        }
        element
    }
}

/** This node's elements, then [next]'s, which is not pulled from, or started, until this one has ended. */
context(ctx: PullContext)
private fun Node.then(next: Node): Pull {
    val first = pull()
    var second: Pull? = null
    return Pull {
        val switched = second
        if (switched != null) return@Pull switched.next()
        first.next() ?: next.pull().also { second = it }.next()
    }
}

context(ctx: PullContext)
private fun Node.ZipWith.zipWith(): Pull {
    val combine = guarded("zipWith", at, f)
    val left = upstream.pull()
    val right = other.pull()
    return Pull {
        val a = left.next() ?: return@Pull null
        val b = right.next() ?: return@Pull null
        combine(a, b)
    }
}

/** A fused run as one pull: the steps are calls in a loop, and an element a filter drops pulls the next. */
context(ctx: PullContext)
private fun Node.Fused.fusedLoop(): Pull {
    val links = steps.map { it.link() }.toTypedArray()
    val up = upstream.pull()
    return Pull {
        var value: Any? = null
        while (value == null) {
            value = up.next() ?: return@Pull null
            var i = 0
            while (value != null && i < links.size) value = links[i++].apply(value)
        }
        value
    }
}

/**
 * A fused step as the loop calls it: the next value, or `null` where a filter dropped the element. Each kind is a class
 * of its own and calls the caller's body from its own line, where [guarded] would call every body the library runs from
 * one: there the JIT sees too many bodies to inline any of them, and here it sees the bodies of one kind.
 */
private sealed class Link {
    abstract fun apply(a: Any): Any?

    class Mapped(private val f: (Any) -> Any, private val at: String) : Link() {
        override fun apply(a: Any): Any? = guarding("map", at, a) { f(a) }
    }

    class Failable(private val f: Failing<Any?>.(Any) -> Any, private val at: String) : Link() {
        private val scope = Failing<Any?>()

        override fun apply(a: Any): Any? = guarding("mapOrFail", at, a) { scope.f(a) }
    }

    class Kept(
        private val predicate: (Any) -> Boolean,
        private val keep: Boolean,
        private val operator: String,
        private val at: String,
    ) : Link() {
        override fun apply(a: Any): Any? = a.takeIf { guarding(operator, at, a) { predicate(a) } == keep }
    }
}

private fun Step.link(): Link =
    when (this) {
        is Step.Map -> Link.Mapped(f, at)
        is Step.MapOrFail -> Link.Failable(f, at)
        is Step.Filter -> Link.Kept(predicate, keep, operator, at)
    }

/** What [guarded] does, written in at the call, so the call to [f] is the caller's own call site. */
// The catch is as wide as guarded's: a defect is everything the caller did not declare.
@Suppress("TooGenericExceptionCaught")
private inline fun <B> guarding(operator: String, at: String, a: Any, f: () -> B): B =
    try {
        f()
    } catch (thrown: Throwable) {
        throw thrown.describedBy(operator, a, at)
    }

/** A probe on the pulling thread: entering `next` ends a wait, and returning an element starts one. */
context(ctx: PullContext)
private fun Node.Probed.probed(): Pull {
    val watch = probe.watch()
    val up = upstream.pull()
    return Pull {
        watch.asked()
        up.next()?.also { watch.emitted() }
    }
}

/**
 * Windows of [Node.Sliding.n], each [Node.Sliding.step] on from the last, as Pekko's `sliding` answers them: a step
 * longer than the window skips what falls between, and the stream's end emits what is left only where it holds elements
 * no window has emitted yet.
 */
context(ctx: PullContext)
private fun Node.Sliding.sliding(): Pull = Windowing(upstream.pull(), n, step)

private class Windowing(private val up: Pull, private val n: Int, private val step: Int) : Pull {

    private val window = ArrayDeque<Any>()

    /** Elements taken since the last window went out: the end emits a short one only if there are any. */
    private var unseen = 0
    private var ended = false

    override fun next(): Any? {
        var emit: List<Any>? = null
        while (emit == null && !ended) {
            val a = up.next()
            if (a == null) {
                ended = true
                emit = window.toList().takeIf { unseen > 0 }
            } else {
                emit = taken(a)
            }
        }
        return emit
    }

    /** Elements still to pass over after a window, where the step is longer than the window. */
    private var toSkip = 0

    /** [a] into the window, and the window if that fills it. Past a full one, the window moves on first. */
    private fun taken(a: Any): List<Any>? {
        if (toSkip > 0) {
            toSkip--
            return null
        }
        window.addLast(a)
        unseen++
        if (window.size < n) return null
        val full = window.toList()
        repeat(minOf(step, n)) { window.removeFirst() }
        toSkip = maxOf(step - n, 0)
        unseen = 0
        return full
    }
}

/**
 * [Node.Interleave.segmentSize] elements from the first stream, then as many from the other, in turn; once either ends,
 * the rest of the other, as Pekko's `interleave` does by default.
 */
context(ctx: PullContext)
private fun Node.Interleave.interleave(): Pull {
    val sides = arrayOf(upstream.pull(), other.pull())
    val done = booleanArrayOf(false, false)
    var side = 0
    var taken = 0
    return Pull {
        var element: Any? = null
        while (element == null && !(done[0] && done[1])) {
            if (done[side]) {
                side = 1 - side
                taken = 0
            }
            element = sides[side].next()
            if (element == null) {
                done[side] = true
            } else if (++taken == segmentSize && !done[1 - side]) {
                side = 1 - side
                taken = 0
            }
        }
        element
    }
}

/**
 * On a test's clock: up to [window] of the caller's stages at once, each started on the pulling thread, answered in the
 * order the elements came. A stage that fails is the defect `mapAsync` names, and one that completes with `null` is
 * too, as on Pekko. The wait is interruptible, so a stop wakes it.
 */
context(ctx: PullContext)
private fun Node.MapAsync.awaiting(window: Int): Pull {
    val start = guarded("mapAsync", at, f)
    val up = upstream.pull()
    val started = ArrayDeque<Pair<Any, CompletableFuture<Any?>>>()
    var drained = false
    return Pull {
        while (!drained && started.size < window) {
            val a = up.next()
            @Suppress("UNCHECKED_CAST")
            if (a == null) drained = true
            else started.addLast(a to (start(a) as CompletionStage<Any?>).toCompletableFuture())
        }
        started.removeFirstOrNull()?.let { (a, stage) ->
            val b =
                try {
                    stage.get()
                } catch (failed: ExecutionException) {
                    throw (failed.cause ?: failed).unwrapped().describedBy("mapAsync", a, at)
                }
            b ?: throw NullPointerException("${facts("mapAsync", a, at)}: the stage completed with null")
        }
    }
}
