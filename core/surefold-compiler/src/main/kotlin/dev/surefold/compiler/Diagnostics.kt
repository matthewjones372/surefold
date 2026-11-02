package dev.surefold.compiler

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import kotlin.reflect.KClass

/**
 * What the plugin can say (spec 0041). A container rather than loose factories because the compiler finds a plugin's
 * renderers through it: a diagnostic whose factory nobody registered prints as its own name and nothing else.
 */
object ForkDiagnostics : KtDiagnosticsContainer() {

    /** A `Deferred`, `Channel` or `Scope` that outlives the scope that ends it. */
    val DEFERRED_ESCAPES_SCOPE: KtDiagnosticFactory1<String> = fault("DEFERRED_ESCAPES_SCOPE")

    /** `await` or `cancel` inside an `async` block on a `Deferred` from outside it. */
    val AWAIT_ACROSS_FORK: KtDiagnosticFactory1<String> = fault("AWAIT_ACROSS_FORK")

    /** A labelled `this@x` of a `Fail` from outside an `async` block, used inside it. */
    val RAISE_ACROSS_FORK: KtDiagnosticFactory1<String> = fault("RAISE_ACROSS_FORK")

    /** A catch-all around `fail`, `bind` or `await` that keeps what it caught; a warning, for a process edge. */
    val CATCH_EATS_RAISE: KtDiagnosticFactory1<String> = KtDiagnosticFactory1(
        "CATCH_EATS_RAISE",
        Severity.WARNING,
        SourceElementPositioningStrategies.DEFAULT,
        psiElement,
        Renderers,
    )

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = Renderers
}

/** An error with one sentence, reported where the offending expression is. */
private fun fault(name: String) = KtDiagnosticFactory1<String>(
    name,
    Severity.ERROR,
    SourceElementPositioningStrategies.DEFAULT,
    psiElement,
    Renderers,
)

/**
 * `PsiElement`, under whichever name it has where this is running: `kotlin-compiler-embeddable` relocates IntelliJ's
 * classes and the IDE does not, so the name is resolved at runtime, as `surefold-app-compiler` does.
 */
private val psiElement: KClass<*> = sequenceOf(
    "org.jetbrains.kotlin.com.intellij.psi.PsiElement",
    "com.intellij.psi.PsiElement",
).firstNotNullOfOrNull(::classNamed)
    ?: error("neither the relocated nor the plain PsiElement is on the classpath")

private fun classNamed(name: String): KClass<*>? =
    try {
        Class.forName(name).kotlin
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: LinkageError) {
        null
    }

private object Renderers : BaseDiagnosticRendererFactory() {

    // The name is the compiler's, on an abstract property this has to override.
    @Suppress("ktlint:standard:property-naming")
    override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("SurefoldForks") { map ->
        map.put(ForkDiagnostics.DEFERRED_ESCAPES_SCOPE, "surefold: {0}", CommonRenderers.STRING)
        map.put(ForkDiagnostics.AWAIT_ACROSS_FORK, "surefold: {0}", CommonRenderers.STRING)
        map.put(ForkDiagnostics.RAISE_ACROSS_FORK, "surefold: {0}", CommonRenderers.STRING)
        map.put(ForkDiagnostics.CATCH_EATS_RAISE, "surefold: {0}", CommonRenderers.STRING)
    }
}
