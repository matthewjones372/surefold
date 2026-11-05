package dev.surefold.app

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * An application as a value: the graph, the node it starts from, and what to do with that node.
 *
 * ```kotlin
 * object Petshop : SurefoldApp<PelicanServer>() {
 *     override val module = petshop
 * }
 * ```
 *
 * A service has no `run`: the default parks until the process is asked to stop, and the graph's release then stops the
 * root (spec 0135). A job overrides `run` with its work, and the graph is released when it returns.
 *
 * The root is named once. A type parameter is erased at a *use* site, which is why `single` and the rest are inline
 * functions — but a type argument written into a *subclass declaration* is kept in the class file, and that is what
 * this reads. Jackson's `TypeReference` and Guice's `TypeLiteral` are the same trick.
 */
abstract class SurefoldApp<A : Any> {

    abstract val module: Module

    /** Parks until the process is asked to stop. Not the root's own wait: that ends on the release, after this. */
    open fun AppScope.run(root: A) = awaitShutdown()

    /** The key `A` names, found among the graph's own keys so that no `KType` has to be built. */
    val root: KType by lazy { rootOf(javaClass, module) }
}

/** [runApp] for an application that declares its own root, so `main` is the leaving and nothing else. */
fun <A : Any> runApp(app: SurefoldApp<A>): ExitCode = leaving { shutdown ->
    app.module.use(app.root) { built ->
        @Suppress("UNCHECKED_CAST") with(app) { appScope(shutdown).run(built as A) }
    }
}

/**
 * The class the subclass wrote between the angle brackets, matched to the key the graph holds.
 *
 * Matched rather than constructed: building a `KType` from a `java.lang.reflect.Type` needs kotlin-reflect, and the
 * graph already holds the key this is looking for.
 */
private fun rootOf(app: Class<*>, module: Module): KType {
    val declared = declaredRoot(app) ?: error("${app.simpleName} must name its root: object App : SurefoldApp<Thing>()")
    val matching = module.nodes.keys.filter { key -> (key.classifier as? KClass<*>)?.java == declared }
    return when (matching.size) {
        1 -> matching.single()

        0 ->
            error(
                "${app.simpleName} starts from ${declared.simpleName}, and its graph builds no such node",
            )

        else ->
            error(
                "${app.simpleName} starts from ${declared.simpleName}, and its graph builds " +
                    "${matching.size} of them: ${matching.joinToString { labelOf(it) }}",
            )
    }
}

private tailrec fun declaredRoot(type: Class<*>?): Class<*>? =
    when {
        type == null -> null

        type.superclass == SurefoldApp::class.java ->
            (type.genericSuperclass as? ParameterizedType)?.actualTypeArguments?.firstOrNull()?.erased()

        else -> declaredRoot(type.superclass)
    }

private fun Type.erased(): Class<*>? =
    when (this) {
        is Class<*> -> this
        is ParameterizedType -> rawType as? Class<*>
        else -> null
    }
