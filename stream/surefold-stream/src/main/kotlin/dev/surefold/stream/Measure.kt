package dev.surefold.stream

import dev.surefold.Counter
import dev.surefold.Histogram
import dev.surefold.Metrics
import dev.surefold.increment
import java.util.concurrent.atomic.AtomicLong

// A duration is recorded in seconds, as surefold's `timed` records it and a backend assumes a duration is.
private const val NANOS_PER_SECOND = 1_000_000_000.0

/** What a stage of a pipeline sampled one element in 64 of, by default: a clock read costs about what fusing saves. */
private const val SAMPLE_EVERY = 64

/**
 * Where a run's numbers go, and what they are filed under. Each stage reports `surefold.stream.elements`, the elements
 * it emitted, and `surefold.stream.busy`, the time inside its own body for one element in [sampleEvery], tagged
 * `pipeline`, `stage`, `at` and `step`, its place in the order data moves.
 */
class Measured(
    val pipeline: String,
    val metrics: Metrics = dev.surefold.metrics.get(),
    val sampleEvery: Int = SAMPLE_EVERY,
) {
    init {
        require(sampleEvery > 0) { "sampleEvery must be at least 1, and was $sampleEvery" }
    }
}

/**
 * This run with every stage measured. Built once and started as often as needed, as any run is: the instruments are
 * looked up here, not per element. An unmeasured run is untouched and pays nothing.
 */
fun <E, R> Run<E, R>.measured(by: Measured): Run<E, R> = Run(node.measuredBy(by), end)

/** [measured], then [start]: for a run started once. */
fun <E, R : Any> Run<E, R>.start(backend: StreamBackend, measured: Measured): Running<E, R> =
    measured(measured).start(backend)

@StreamSpi
fun Node.measuredBy(by: Measured): Node {
    val order = dataOrder()
    val instruments = order.mapIndexed { step, node -> Instruments(by, node, step) }
    val of = { node: Node -> instruments[order.indexOfFirst { it === node }] }
    val measured = measured(of)
    // A pipeline that ends in a fusable run has no stage after it to put the run's probe before.
    return if (isStep()) Node.Probed(measured, of(this).probe(counts = false)) else measured
}

/**
 * Every node once, each after the nodes it reads from: the order data moves, and the order a rendering and a
 * measurement number the stages in.
 */
@StreamSpi
fun Node.dataOrder(): List<Node> =
    children()
        .fold(emptyList<Node>()) { seen, child ->
            seen + child.dataOrder().filter { n -> seen.none { it === n } }
        }
        .let { before -> if (before.any { it === this }) before else before + this }

/**
 * What a backend calls as elements pass a [Node.Probed]: [Watch.emitted] as one goes by, [Watch.asked] when the stage
 * after it asks for the next. A backend takes one [watch] per run, so two runs of one measured description never share
 * a timestamp.
 */
@StreamSpi
class Probe
internal constructor(
    private val elements: Counter?,
    private val waiting: Lazy<Histogram>,
    private val every: Long,
) {
    fun watch(): Watch = Watch()

    inner class Watch internal constructor() {
        private val emitted = AtomicLong()
        private val since = AtomicLong(NOT_WAITING)

        /** An element went downstream; one in [every] starts a wait for the next demand. */
        fun emitted() {
            elements?.increment()
            if (emitted.incrementAndGet() % every == 0L) since.set(System.nanoTime())
        }

        /** Downstream asked for the next element: a wait that was started ends here. */
        fun asked() {
            val started = since.getAndSet(NOT_WAITING)
            if (started != NOT_WAITING) waiting.value.record((System.nanoTime() - started) / NANOS_PER_SECOND)
        }
    }
}

private const val NOT_WAITING = -1L

/** One stage's instruments. Each histogram is looked up on its first sample, so a stage reports only what it has. */
private class Instruments(by: Measured, node: Node, step: Int) {
    private val tags =
        mapOf(
            "pipeline" to by.pipeline,
            "stage" to node.operator,
            "at" to (node.site ?: "-"),
            "step" to step.toString(),
        )
    val elements: Counter = by.metrics.counter("surefold.stream.elements", tags)
    private val busy: Histogram by lazy { by.metrics.histogram("surefold.stream.busy", tags) }
    private val waiting: Lazy<Histogram> = lazy { by.metrics.histogram("surefold.stream.waiting", tags) }
    private val every = by.sampleEvery.toLong()
    private val seen = AtomicLong()

    /** [body], timed when this element is the one in [every] that is sampled. */
    fun <T> timed(body: () -> T): T {
        if (seen.incrementAndGet() % every != 0L) return body()
        val started = System.nanoTime()
        try {
            return body()
        } finally {
            busy.record((System.nanoTime() - started) / NANOS_PER_SECOND)
        }
    }

    /** A probe for this stage; [counts] where nothing inside the stage counts its elements already. */
    fun probe(counts: Boolean): Probe = Probe(elements.takeIf { counts }, waiting, every)
}

/** The operators fusing merges, which count inside their own bodies and take one probe after the whole run. */
private fun Node.isStep(): Boolean =
    this is Node.Map || this is Node.MapOrFail || this is Node.Filter || this is Node.FilterNot

/**
 * The tree with each stage's body timed and its elements counted and watched. A run of fusable operators counts inside
 * its bodies and is watched by one probe after its last step, so a measured run fuses as an unmeasured one does; every
 * other stage is followed by a probe of its own.
 */
private fun Node.measured(of: (Node) -> Instruments): Node {
    val stage = of(this)
    // A fusable child of a stage that is not one ends a run, and the run's probe goes after it.
    val child = { c: Node ->
        c.measured(of).let { m -> if (c.isStep() && !isStep()) Node.Probed(m, of(c).probe(counts = false)) else m }
    }
    return when (this) {
        is Node.Map -> Node.Map(child(upstream), { a -> stage.timed { f(a) }.also { stage.elements.increment() } }, at)

        is Node.MapOrFail ->
            Node.MapOrFail(
                child(upstream),
                { a -> stage.timed { f(a) }.also { stage.elements.increment() } },
                at,
            )

        is Node.Filter -> Node.Filter(child(upstream), stage.keeping(predicate, keep = true), at)

        is Node.FilterNot -> Node.FilterNot(child(upstream), stage.keeping(predicate, keep = false), at)

        is Node.MapConcat ->
            Node.Probed(
                Node.MapConcat(child(upstream), { a -> stage.timed { f(a) } }, at),
                stage.probe(counts = true),
            )

        is Node.MapPar ->
            Node.Probed(
                Node.MapPar(child(upstream), parallelism, on, { a -> stage.timed { f(a) } }, at, key, ordered),
                stage.probe(counts = true),
            )

        Node.Hole -> this

        is Node.Native,
        is Node.Elements,
        is Node.Single,
        is Node.Tick,
        is Node.FromStage,
        is Node.Fail,
        is Node.Blocking,
        Node.Empty,
        is Node.Merge,
        is Node.Interleave,
        is Node.ZipWith,
        is Node.Prepend,
        is Node.Concat,
        is Node.RestartOnDefect,
        is Node.Stage,
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
        is Node.MapAsync,
        is Node.Conflate,
        is Node.Either,
        is Node.Absolve,
        is Node.CatchAll,
        is Node.MapError,
        is Node.OrFailIfEmpty,
        is Node.FlatMap,
        is Node.Fused,
        is Node.Probed -> Node.Probed(withChildren(child), stage.probe(counts = true))
    }
}

private fun Instruments.keeping(predicate: (Any) -> Boolean, keep: Boolean): (Any) -> Boolean = { a ->
    timed { predicate(a) }.also { kept -> if (kept == keep) elements.increment() }
}
