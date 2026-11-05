package dev.surefold.app

import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * A setting that picks a module, made inside the graph (spec 0136): a node that depends on [S], and whose value is the
 * module surefold builds into the graph once [S] is built. Every branch provides [P], which is what the rest of the
 * graph is checked against before anything runs; a branch that does not refuses the start.
 *
 * ```kotlin
 * val bus: Module = config<BusSettings>("bus") { busSettings() } +
 *     choice<BusSettings, EventBus> { chosen -> if (chosen.onKafka) kafkaBus(chosen) else inProcessBus }
 * ```
 */
inline fun <reified S : Any, reified P : Any> choice(noinline pick: (S) -> Module): Module =
    choiceOf(typeOf<P>(), listOf(typeOf<S>())) { dependencies -> pick(dependencies[0] as S) }

@PublishedApi
internal fun choiceOf(provides: KType, dependencies: List<KType>, pick: (List<Any>) -> Module): Module =
    Module.of(Node(provides, dependencies, callSite(), { error("a choice is expanded, not built") }, Choice(pick)))
