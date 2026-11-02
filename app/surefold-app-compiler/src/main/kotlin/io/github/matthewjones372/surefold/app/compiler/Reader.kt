package io.github.matthewjones372.surefold.app.compiler

import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirTypeOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.lazyResolveToPhase
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * The first shape the reader did not know, kept so that `verbose` can say why it stayed quiet.
 *
 * A reader that gives up silently is one nobody can tell from a reader that found nothing wrong.
 */
internal class Gave {
    var at: String? = null

    fun <T> up(what: String): T? {
        if (at == null) at = what
        return null
    }
}

/**
 * The file being checked, and the last reference in it that a read passed through.
 *
 * A diagnostic is reported into one file, so a source element from another lands at that offset in
 * this one — which is how a fault in `Arrivals.kt` came out underlined in an import. A read that
 * leaves the file keeps the reference that took it there and anchors everything it finds on that.
 */
internal class Here(
    private val session: FirSession,
    private val path: String?,
    private val at: KtSourceElement? = null,
) {

    /** The last reference seen while still inside the file being checked. */
    fun at(source: KtSourceElement?): Here = if (at == null) Here(session, path, source) else this

    /** Reading on, still anchored where this file mentioned it unless the declaration is in it. */
    fun inside(symbol: FirCallableSymbol<*>): Here = if (pathOf(symbol) == path) Here(session, path) else this

    /** What was read beyond this file, reported where this file mentions it. */
    fun anchor(graph: Graph): Graph = when (at) {
        null -> graph

        else -> Graph(
            graph.provides.mapValues { at },
            graph.needs.map { Need(it.key, it.by, at) },
            graph.shadows.map { Shadow(it.key, at, at) },
            graph.alternatives,
        )
    }

    private fun pathOf(symbol: FirCallableSymbol<*>): String? =
        session.firProvider.getFirCallableContainerFile(symbol)?.sourceFile?.path

    internal companion object {
        fun of(session: FirSession, file: FirFile?): Here = Here(session, file?.sourceFile?.path)
    }
}

/** A key something needs, and where the recipe that needs it was written. */
internal class Need(val key: String, val by: String, val at: KtSourceElement?)

/** A key two merged graphs both provided, and where each was written. */
internal class Shadow(val key: String, val shadowed: KtSourceElement?, val wins: KtSourceElement?)

/**
 * What an expression builds: each key and the call that built it, and what those recipes ask for.
 *
 * [alternatives] are the keys a choice merged. Every branch of an `if` is read into one graph,
 * because a key is missing only where all of them miss it — but only one branch is ever assembled,
 * so a key two branches provide is one recipe and not a collision.
 */
internal class Graph(
    val provides: Map<String, KtSourceElement?>,
    val needs: List<Need>,
    val shadows: List<Shadow> = emptyList(),
    val alternatives: Set<String> = emptySet(),
) {

    // A key provided twice keeps the later site, which is the one `Module.plus` keeps the node of.
    operator fun plus(other: Graph): Graph = Graph(
        provides + other.provides,
        needs + other.needs,
        // Kept rather than discarded, as `Module.plus` keeps them: a merge cannot tell a deliberate
        // override from a typo, and only `overriding` knows which it was.
        shadows + other.shadows + collisions(other),
        alternatives + other.alternatives,
    )

    /** [plus] with this merge's own collisions forgiven, which is what `Module.shadowing` does. */
    fun overriding(other: Graph): Graph =
        Graph(provides + other.provides, needs + other.needs, shadows + other.shadows, alternatives)

    /** One branch of a choice merged with the others: the keys they share are the same recipe. */
    fun or(other: Graph): Graph = Graph(
        provides + other.provides,
        needs + other.needs,
        shadows + other.shadows,
        alternatives + other.alternatives + provides.keys.intersect(other.provides.keys),
    )

    private fun collisions(other: Graph): List<Shadow> =
        other.provides.keys.filter { it in provides }.map { Shadow(it, provides[it], other.provides[it]) }

    /** The keys this and [other] would collide on where one of them came out of a choice. */
    fun ambiguous(other: Graph): String? =
        provides.keys.intersect(other.provides.keys)
            .firstOrNull { it in alternatives || it in other.alternatives }

    /** The same graph under one key instead of the one it had, which is what `boundTo` does. */
    fun keyedAs(key: String): Graph = Graph(
        mapOf(key to provides.values.singleOrNull()),
        needs.map { Need(it.key, key, it.at) },
        shadows,
        if (alternatives.isEmpty()) emptySet() else setOf(key),
    )

    /** What nothing here builds. A key is missing only where every branch of a choice misses it. */
    fun missing(): List<Need> = needs.filterNot { it.key in provides || it.key in RUNTIME_PROVIDED }

    /**
     * The nodes [root] does not reach, each the top of what it took with it.
     *
     * `Findings.forgotten` names the same set for the same graph at runtime, subtree tops included:
     * a module left out takes everything under it, and naming all of them is one edit reported as
     * nine.
     */
    fun unreached(root: String): List<String> {
        val edges = needs.groupBy({ it.by }, { it.key })
        val lost = provides.keys - reached(edges, setOf(root), setOf(root))
        return lost.filterNot { key -> lost.any { it != key && key in edges[it].orEmpty() } }.sorted()
    }

    /**
     * One ring among the keys this builds, or null where there is none to be sure of.
     *
     * Kahn's algorithm, as `Plan.kt` runs it, over the same edges and taking the same first key: a
     * ring reported here and a ring reported there are the same list in the same order. A ring
     * through a key a choice merged is one the union may have closed itself, and is not reported.
     */
    fun cycle(): List<String>? {
        val edges = needs.groupBy({ it.by }, { it.key })
        val stalled = stalled(provides.keys.associateWith { edges[it].orEmpty().toSet() - RUNTIME_PROVIDED })
        val ring = stalled?.let { walk(it, it.keys.sorted().first(), emptyList()) } ?: return null
        return if (ring.any { it in alternatives }) null else ring
    }

    internal companion object {
        val nothing = Graph(emptyMap(), emptyList())
    }
}

/** What is left when nothing is ready, which is what holds the ring, or null where nothing is. */
private tailrec fun stalled(remaining: Map<String, Set<String>>): Map<String, Set<String>>? {
    val ready = remaining.filterValues { it.isEmpty() }.keys
    return when {
        remaining.isEmpty() -> null
        ready.isEmpty() -> remaining
        else -> stalled((remaining - ready).mapValues { (_, needs) -> needs - ready })
    }
}

/** Every key left needs another that is also left, so following one far enough repeats. */
private tailrec fun walk(edges: Map<String, Set<String>>, at: String, seen: List<String>): List<String> =
    when (at) {
        in seen -> seen.dropWhile { it != at } + at
        else -> walk(edges, edges.getValue(at).sorted().first(), seen + at)
    }

private tailrec fun reached(edges: Map<String, List<String>>, found: Set<String>, frontier: Set<String>): Set<String> {
    val next = frontier.flatMap { edges[it].orEmpty() }.toSet() - found
    return if (next.isEmpty()) found else reached(edges, found + next, next)
}

/**
 * The graph an expression builds, or null where it cannot be read.
 *
 * Null is the whole design. A reader that guessed would put a red line under working code, and a
 * reader that gives up costs nothing, because `surefoldWiring` still runs the graph and fails the build.
 * So every shape not listed here abandons the application rather than assuming it provides nothing.
 */
internal fun read(
    expression: FirExpression,
    gave: Gave,
    here: Here,
    seen: Set<FirCallableSymbol<*>> = emptySet(),
): Graph? =
    when (expression) {
        is FirFunctionCall -> call(expression, gave, here, seen)

        is FirPropertyAccessExpression -> through(expression.symbol(), gave, here.at(expression.source), seen)

        // `if` is a `when` in FIR. A branch that cannot be read makes the choice unreadable, since
        // the key it would have provided is exactly what decides whether another is missing.
        is FirWhenExpression ->
            expression.branches
                .fold(Graph.nothing as Graph?) { all, branch ->
                    val result = branch.result.singleExpression() ?: return gave.up("a branch of several statements")
                    all?.let { read(result, gave, here, seen)?.or(it) }
                }

        // `single { ... } as Module` and its like: the cast says nothing the operand did not.
        is FirTypeOperatorCall -> expression.arguments.singleOrNull()?.let { read(it, gave, here, seen) }

        else -> gave.up(expression::class.simpleName ?: "an expression")
    }

private fun FirPropertyAccessExpression.symbol(): FirPropertySymbol? =
    calleeReference.toResolvedCallableSymbol() as? FirPropertySymbol

/** A `val` or a function returning a module: read what it was written as, once. */
@OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)
private fun through(symbol: FirCallableSymbol<*>?, gave: Gave, here: Here, seen: Set<FirCallableSymbol<*>>): Graph? {
    if (symbol == null) return gave.up("a reference that did not resolve")
    if (symbol in seen) return gave.up("${symbol.callableId}, which refers to itself")
    symbol.lazyResolveToPhase(FirResolvePhase.BODY_RESOLVE)
    val written = when (symbol) {
        is FirPropertySymbol -> symbol.fir.initializer
        is FirNamedFunctionSymbol -> symbol.fir.body?.singleExpression()
        else -> null
    } ?: return gave.up("${symbol.callableId}, which has no source here")
    // The place the reading moves to, which is also the place its findings are reported: within the
    // file being checked there is no anchor and every recipe keeps its own line; beyond it, the
    // reference that led here is the only source this file can be told about.
    val target = here.inside(symbol)
    return read(written, gave, target, seen + symbol)?.let { target.anchor(it) }
}

/** A block that is one expression, which is what a `= ...` function and a `when` branch are. */
private fun FirBlock.singleExpression(): FirExpression? =
    statements.singleOrNull().let { it as? FirExpression ?: (it as? FirReturnExpression)?.result }

/**
 * A call, which is either a factory that builds a node or a combinator over modules already read.
 *
 * Split that way because only the second kind needs the expressions around it: a factory is decided
 * by its name and its type arguments alone.
 */
private fun call(call: FirFunctionCall, gave: Gave, here: Here, seen: Set<FirCallableSymbol<*>>): Graph? {
    val called = call.calleeReference.toResolvedCallableSymbol()?.callableId
        ?: return gave.up("a call that did not resolve")
    val arguments = call.typeArguments.map { projection ->
        (projection as? FirTypeProjectionWithVariance)?.typeRef?.coneType?.let(::keyOf)
            ?: return gave.up("a type argument of $called")
    }

    return factory(called, arguments, call.source)
        ?: combinator(call, called, arguments, gave, here, seen)
        ?: gave.up("$called, which is not a surefold factory")
}

/** What a call builds on its own. Its type arguments are `A` and then what the recipe takes. */
private fun factory(called: CallableId, arguments: List<String>, at: KtSourceElement?): Graph? {
    val key = arguments.firstOrNull()
    val rest = arguments.drop(1)
    return when {
        called == SINGLE || called == SINGLE_OF || called == CLOSING_OF -> key?.let { node(it, rest, at) }

        // choice<S, P>: a node providing P that needs S; which branch is built is a start's business (spec 0136).
        called == CHOICE -> rest.singleOrNull()?.let { provided ->
            key?.let { setting -> node(provided, listOf(setting), at) }
        }

        called in CONFIGS -> key?.let { node(it, listOf(TYPESAFE_CONFIG), at) }

        called == ACTOR -> key?.let { node("$ACTOR_REF<$it>", listOf(ACTOR_SYSTEM) + rest, at) }

        called == ACTORS -> node(SUREFOLD_ACTORS, emptyList(), at)

        called == SUREFOLD_ACTOR -> key?.let { node("$SUREFOLD_ACTOR_REF<$it>", listOf(SUREFOLD_ACTORS) + rest, at) }

        // Either needs the graph's DataSource, or with connect, `{ s: S -> source }`, its S instead.
        called == MIGRATIONS || called == FLYWAY -> node(MIGRATED, arguments.ifEmpty { listOf(DATA_SOURCE) }, at)

        // openFeature { provider } needs nothing; openFeature { d: D -> provider } needs its D.
        called == OPEN_FEATURE -> node(OPENFEATURE_CLIENT, arguments, at)

        called == FLAGD_FLAGS -> node(OPENFEATURE_CLIENT, listOf(TYPESAFE_CONFIG), at)

        called == JOB_WORKER -> node(JOBS_WORKER, listOf(DATA_SOURCE, MIGRATED, JOBS_SETTINGS), at)

        // A source provides its Layered and the merge of it, which is the Config: reaching one reaches both.
        called in SOURCES -> node(TYPESAFE_CONFIG, listOf(LAYERED), at) + node(LAYERED, emptyList(), at)

        else -> null
    }
}

/** What a call does to modules already built: a merge, a re-keying, or nothing at all. */
private fun combinator(
    call: FirFunctionCall,
    called: CallableId,
    arguments: List<String>,
    gave: Gave,
    here: Here,
    seen: Set<FirCallableSymbol<*>>,
): Graph? {
    val left = { call.explicitReceiver?.let { read(it, gave, here, seen) } }
    val right = {
        call.arguments.singleOrNull()?.let { read(it, gave, here, seen) } ?: gave.up<Graph>("a merge of no one thing")
    }
    return when (called) {
        PLUS -> left()?.let { base -> right()?.let { merged(base, it, gave) } }
        OVERRIDING -> left()?.let { base -> right()?.let { base.overriding(it) } }
        OVERRIDING_CONFIG -> left()?.overriding(node(LAYERED, emptyList(), call.source))
        BOUND_TO -> arguments.singleOrNull()?.let { key -> left()?.keyedAs(key) }
        PROBE -> left()
        else -> null
    }
}

/**
 * Two modules merged, unless one of them came out of a choice.
 *
 * A key provided both inside a choice and outside one collides in some assemblies and not in
 * others, and the reader cannot say which it is looking at. Neither answer is worth a warning.
 */
private fun merged(base: Graph, other: Graph, gave: Gave): Graph? =
    base.ambiguous(other)
        ?.let { gave.up<Graph>("$it, provided both inside a choice and outside one") }
        ?: (base + other)

private fun node(key: String, dependencies: List<String>, at: KtSourceElement?): Graph =
    Graph(mapOf(key to at), dependencies.map { Need(it, key, at) })

private const val APP = "io.github.matthewjones372.surefold.app"

private fun app(name: String) = CallableId(FqName(APP), Name.identifier(name))

// Spelled out as `extension("module", "name")` at every use: ReaderKnowsFactoriesTest reads them from this file.
private fun extension(module: String, name: String) = CallableId(FqName("$APP.$module"), Name.identifier(name))

private val SINGLE = app("single")
private val SINGLE_OF = app("singleOf")
private val CLOSING_OF = app("closingOf")
private val CHOICE = app("choice")
private val BOUND_TO = app("boundTo")
private val PROBE = app("probe")
private val OVERRIDING = app("overriding")
private val PLUS = CallableId(ClassId(FqName(APP), Name.identifier("Module")), Name.identifier("plus"))

private val ACTOR = extension("pekko", "actor")
private val ACTORS = extension("actor", "actors")
private val SUREFOLD_ACTOR = extension("actor", "actor")

/** Every `config`, the block form and the class form alike: each is a node built from a `Config`. */
private val CONFIGS = setOf(
    extension("typesafe", "config"),
    extension("typesafe", "configured"),
    extension("kotlinx", "config"),
)

private val OVERRIDING_CONFIG = extension("typesafe", "overridingConfig")
private val MIGRATIONS = extension("liquibase", "migrations")
private val FLYWAY = extension("flyway", "flyway")
private val OPEN_FEATURE = extension("openfeature", "openFeature")
private val FLAGD_FLAGS = extension("flagd", "flagdFlags")
private val JOB_WORKER = extension("jobs", "jobWorker")

/**
 * The factories surefold ships whose bodies are not in the source being compiled.
 *
 * A library function is a symbol with no initialiser to read, so the reader would give up on a graph
 * for using one — and every graph that reads configuration at all starts with one of these. They are
 * listed rather than read because what each builds is part of surefold's own API.
 */
private val SOURCES = setOf(
    extension("typesafe", "loadedConfig"),
    extension("typesafe", "configOf"),
    extension("typesafe", "configFromResource"),
    extension("typesafe", "configFromFile"),
)

/** What a started graph hands its nodes without any module providing it; `surefold-app` says the same. */
private val RUNTIME_PROVIDED = setOf("$APP.HealthRegistry")

private const val ACTOR_REF = "org.apache.pekko.actor.typed.ActorRef"
private const val ACTOR_SYSTEM = "org.apache.pekko.actor.ActorSystem"
private const val SUREFOLD_ACTOR_REF = "io.github.matthewjones372.surefold.actor.ActorRef"
private const val SUREFOLD_ACTORS = "$APP.actor.Actors"
private const val TYPESAFE_CONFIG = "com.typesafe.config.Config"
private const val LAYERED = "$APP.typesafe.Layered"
private const val DATA_SOURCE = "javax.sql.DataSource"
private const val MIGRATED = "$APP.Migrated"
private const val OPENFEATURE_CLIENT = "dev.openfeature.sdk.Client"
private const val JOBS_WORKER = "dev.surefold.jobs.JobWorker"
private const val JOBS_SETTINGS = "dev.surefold.jobs.JobSettings"
