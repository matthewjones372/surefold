package io.github.matthewjones372.surefold.app

import dev.surefold.ExitCase
import dev.surefold.ResourceScope
import dev.surefold.Shared
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.NonEmptyList
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.fold
import dev.surefold.fail.mapLeft
import dev.surefold.parMap
import dev.surefold.resourceScope
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/** Why an application did not start. */
sealed class StartupError {

    /** The graph was faulty before anything was built. [provided] is what it did hold. */
    data class Unwireable(val errors: NonEmptyList<WiringError>, val provided: Set<KType> = emptySet()) :
        StartupError()

    /** A recipe declined, naming [reason]. */
    data class Refused(val key: KType, val reason: String) : StartupError()

    /** Nothing in the graph builds [key], and it was asked for. */
    data class NoSuchNode(val key: KType) : StartupError()

    /**
     * [key] was built, and the probe named [name] did not answer yes inside its timeout. [cause] is what its last
     * attempt threw instead of answering, if it threw.
     */
    data class Unready(val key: KType, val name: String, val cause: Exception? = null) : StartupError()
}

/**
 * What a recipe is handed: somewhere to install a release, and a way to decline.
 *
 * Not also a `Fail<StartupError>`: `ResourceScope.bind` takes a `Resource<A>` and `Raise.bind` a
 * function too, and the two erase to one JVM signature.
 */
interface Wiring : ResourceScope {

    /** Ends the start, naming this node and [reason]. */
    fun refuse(reason: String): Nothing

    /**
     * Runs [started] once every node is built, in the graph's order, before any `WhenStarted` probe (spec 0129). One
     * that throws ends the start as this node's refusal. Asked for once the graph has started, it throws.
     */
    fun onStarted(started: () -> Unit)
}

/** Starts the graph, hands [block] the node it asks for, and gives everything back on the way out. */
inline fun <reified A : Any, B> Module.use(noinline block: (A) -> B): Either<StartupError, B> =
    use(typeOf<A>()) {
        @Suppress("UNCHECKED_CAST")
        block(it as A)
    }

@PublishedApi
internal fun <B> Module.use(root: KType, block: (Any) -> B): Either<StartupError, B> = attempt {
    validate().mapLeft { faults -> StartupError.Unwireable(faults, nodes.keys) }.bind()
    if (!nodes.containsKey(root)) fail(StartupError.NoSuchNode(root))

    val health = HealthRegistry(probes)
    // The order nodes were built in, layer by layer: a choice's branch is only known once its setting is built.
    val order = CopyOnWriteArrayList<KType>()
    resourceScope {
        val releases = Releases()
        // One release on surefold's scope, installed before anything is acquired: it decides the ExitCase,
        // and the order below is this graph's rather than whichever fork happened to finish first.
        onRelease { exit -> releases.release(order.toList(), exit) }
        val starts = Starts()
        val built = build(this@use, releases, starts, health, order)
        starts.run(order.toList(), this@attempt)
        probes.filter { it.asked == Asked.WhenStarted }.forEach { probe ->
            val answer = answers(probe, built.getValue(probe.key))
            if (answer is Answer.No) fail(StartupError.Unready(probe.key, probe.name, answer.cause))
        }
        health.running()
        block(built.getValue(root))
    }
}

/**
 * A layer at a time: every node whose dependencies are built. A choice whose dependencies are built is expanded
 * first, its branch taking its place, and the layers are worked out again with the branch in them.
 */
private fun Fail<StartupError>.build(
    start: Module,
    releases: Releases,
    starts: Starts,
    health: HealthRegistry,
    order: MutableList<KType>,
): Map<KType, Any> {
    var module = start
    var built = mapOf<KType, Any>(typeOf<HealthRegistry>() to health)
    while (true) {
        val remaining = module.nodes.values.filter { it.key !in built }
        if (remaining.isEmpty()) {
            module.pending.keys.firstOrNull()?.let { key ->
                fail(StartupError.Refused(key, "it was overridden, and no chosen branch provides it"))
            }
            return built
        }
        val ready = remaining.filter { node -> node.dependencies.all { it in built } }
        val (choices, nodes) = ready.partition { it.choice != null }
        when {
            choices.isNotEmpty() -> module = choices.fold(module) { m, choice -> expanded(m, choice, built, health) }

            nodes.isEmpty() -> fail(
                StartupError.Refused(
                    remaining.first().key,
                    "a chosen branch left ${remaining.joinToString { labelOf(it.key) }} needing each other",
                ),
            )

            else -> {
                val layer = nodes.map { it.key }.sortedBy { it.toString() }
                val current = module
                // Each node fails into its own fork's scope, never into this one from another thread.
                built = built + attempt {
                    parMap(layer) { key ->
                        val node = current.nodes.getValue(key)
                        val wiring = NodeWiring(key, releases, starts, this)
                        val value = node.build(wiring, node.dependencies.map(built::getValue))
                        current.probes.filter { it.key == key && it.asked == Asked.WhenBuilt }.forEach { probe ->
                            val answer = answers(probe, value)
                            if (answer is Answer.No) fail(StartupError.Unready(key, probe.name, answer.cause))
                        }
                        health.started(key, value)
                        key to value
                    }
                }.fold({ fail(it) }, { it })
                order += layer
            }
        }
    }
}

/**
 * [module] with [choice]'s branch in its place. A branch node never replaces one the graph already holds under its
 * key, so what a test overrode stays overridden; a branch that does not provide the choice's key, or needs what
 * nothing builds, refuses the start under that key.
 */
private fun Fail<StartupError>.expanded(
    module: Module,
    choice: Node,
    built: Map<KType, Any>,
    health: HealthRegistry,
): Module {
    val key = choice.key
    val branch = checkNotNull(choice.choice).pick(choice.dependencies.map(built::getValue))
    if (key !in branch.nodes) fail(StartupError.Refused(key, "the branch chosen for it does not provide it"))
    // What a test overrode wins: a node already in the graph, or one waiting for a branch to bring its key.
    val added = branch.nodes.filterKeys { it == key || it !in module.nodes }
        .mapValues { (brought, node) -> module.pending[brought] ?: node }
    val missing = added.values.flatMap { it.dependencies }
        .filterNot { it in module.nodes || it in added || it in runtimeProvided }
        .distinct()
    if (missing.isNotEmpty()) {
        fail(
            StartupError.Refused(
                key,
                "the branch chosen for it needs ${missing.joinToString(transform = ::labelOf)}, and nothing builds it",
            ),
        )
    }
    val probes = branch.probes.filter { it.key in added }
    health.adding(probes)
    return Module.of((module.nodes - key) + added, module.probes + probes, module.shadows, module.pending - added.keys)
}

/**
 * Asked again up to [Probe.attempts] times, waiting out [Probe.interval] on the inherited clock. A throw is asked
 * again like a no, since a pool whose database is still coming up throws rather than answering.
 */
private fun answers(probe: Probe, value: Any): Answer {
    var last: Answer = Answer.No(null)
    repeat(probe.attempts) { attempt ->
        last = probe.answered(value)
        if (last == Answer.Yes) return last
        if (attempt < probe.attempts - 1) clock.get().sleep(probe.interval)
    }
    return last
}

private class NodeWiring(
    private val key: KType,
    private val releases: Releases,
    private val starts: Starts,
    private val failing: Fail<StartupError>,
) : Wiring {

    override fun onRelease(release: (ExitCase) -> Unit) = releases.add(key, release)

    override fun onStarted(started: () -> Unit) = starts.add(key, started)

    // A hook runs after the build's own scope has ended, so its refusal goes to the one running the hooks.
    override fun refuse(reason: String): Nothing =
        if (starts.running) throw Refusal(key, reason) else failing.fail(StartupError.Refused(key, reason))
}

/** A refusal from inside an `onStarted` hook, on its way to [Starts.run]. */
private class Refusal(val key: KType, val reason: String) : RuntimeException(reason)

/**
 * The releases of every node, kept by the node that installed them, because a layer's forks install at
 * once and acquisition order under `parMap` is whichever thread got there first.
 */
private class Releases {

    private val byKey = Shared(emptyMap<KType, List<(ExitCase) -> Unit>>(), name = "surefold.app.releases")

    fun add(key: KType, release: (ExitCase) -> Unit) {
        byKey.update { held -> held + (key to held[key].orEmpty() + release) }
    }

    /**
     * Reverse topological, so a node is given back only after everything that needed it. A node of the layer that
     * failed is not in [order], but may have installed before it or a sibling refused: it goes first, since nothing
     * that was built depends on it.
     */
    fun release(order: List<KType>, exit: ExitCase) {
        val held = byKey.peek()
        val unfinished = held.keys - order.toSet()
        val installed = (unfinished + order.reversed()).flatMap { held[it].orEmpty().reversed() }
        val failures = installed.mapNotNull { release -> thrownBy(release, exit) }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }
}

/** What each node asked to run once the graph is built, kept by node so they run in the graph's order. */
private class Starts {

    // The hooks by node, and whether they have been run: an add after the run is refused in the same step.
    private val held = Shared(emptyMap<KType, List<() -> Unit>>() to false, name = "surefold.app.starts")

    @Volatile
    var running = false
        private set

    // A hook asked for after the run would never run, and nothing would say so.
    fun add(key: KType, started: () -> Unit) {
        held.update { (byKey, ran) ->
            check(!ran) { "${labelOf(key)} asked for onStarted after the graph had started" }
            byKey + (key to byKey[key].orEmpty() + started) to false
        }
    }

    /** Runs every hook, in [order]; one that throws ends the start, naming its node, as a refusal does. */
    fun run(order: List<KType>, failing: Fail<StartupError>) {
        val hooks = held.modify { (byKey, _) ->
            (byKey to true) to order.flatMap { key -> byKey[key].orEmpty().map { key to it } }
        }
        running = true
        try {
            hooks.forEach { (key, started) -> runHook(key, started, failing) }
        } finally {
            running = false
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runHook(key: KType, started: () -> Unit, failing: Fail<StartupError>) {
        try {
            started()
        } catch (refused: Refusal) {
            failing.fail(StartupError.Refused(refused.key, refused.reason))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (thrown: Exception) {
            val why = "its onStarted threw ${thrown::class.simpleName}: ${thrown.message}"
            failing.fail(StartupError.Refused(key, why))
        }
    }
}

/** What a release threw, if anything. Every release runs whatever one before it threw, so none is let off. */
@Suppress("TooGenericExceptionCaught")
private fun thrownBy(release: (ExitCase) -> Unit, exit: ExitCase): Throwable? =
    try {
        release(exit)
        null
    } catch (thrown: Throwable) {
        thrown
    }
