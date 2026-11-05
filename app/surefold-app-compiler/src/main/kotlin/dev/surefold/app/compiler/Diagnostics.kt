package dev.surefold.app.compiler

import kotlin.reflect.KClass
import org.jetbrains.kotlin.diagnostics.AbstractSourceElementPositioningStrategy
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers

/**
 * What the plugin can say.
 *
 * A container rather than loose factories because the compiler finds a plugin's renderers through it: a diagnostic
 * whose factory nobody registered prints as its own name and nothing else.
 */
object SurefoldDiagnostics : KtDiagnosticsContainer() {

    /** What was read, when asked. Off by default, because a build that is working has nothing to say. */
    val SUREFOLD_APP_FOUND: KtDiagnosticFactory1<String> =
        factory("SUREFOLD_APP_FOUND", Severity.WARNING, SourceElementPositioningStrategies.DECLARATION_NAME)

    /**
     * A graph this could not read, said whether or not anybody asked.
     *
     * Otherwise silence means two things — the graph is sound, and the graph was never looked at — and a reader who
     * takes the first for the second has been told the opposite of the truth.
     */
    val SUREFOLD_APP_UNREAD: KtDiagnosticFactory1<String> =
        factory("SUREFOLD_APP_UNREAD", Severity.WARNING, SourceElementPositioningStrategies.DECLARATION_NAME)

    /** A key nothing in the graph provides. */
    // Reported on the call that asked, which is not a declaration: `DECLARATION_NAME` casts its
    // source to one, and the editor drops every diagnostic whose strategy throws. The compiler is
    // more forgiving, so the cost of getting this wrong is silence in the only place it matters.
    val SUREFOLD_APP_MISSING: KtDiagnosticFactory1<String> =
        factory("SUREFOLD_APP_MISSING", Severity.ERROR, SourceElementPositioningStrategies.DEFAULT)

    /** A node the root does not reach. A warning, because `rootFindings` gives it `Severity.WARN`. */
    val SUREFOLD_APP_UNREACHABLE: KtDiagnosticFactory1<String> =
        factory("SUREFOLD_APP_UNREACHABLE", Severity.WARNING, SourceElementPositioningStrategies.DEFAULT)

    /** A key two recipes provide. A warning, as `Findings.kt` gives a `Duplicate` `Severity.WARN`. */
    val SUREFOLD_APP_DUPLICATE: KtDiagnosticFactory1<String> =
        factory("SUREFOLD_APP_DUPLICATE", Severity.WARNING, SourceElementPositioningStrategies.DEFAULT)

    /** A ring of recipes. An error, as `Findings.kt` gives a `Cycle` `Severity.FAIL`. */
    val SUREFOLD_APP_CYCLE: KtDiagnosticFactory1<String> =
        factory("SUREFOLD_APP_CYCLE", Severity.ERROR, SourceElementPositioningStrategies.DEFAULT)

    /** A test waiting a fixed time where `eventually` waits only as long as it takes. Test compilations only. */
    val SUREFOLD_SLEEP_IN_TEST: KtDiagnosticFactory1<String> =
        factory("SUREFOLD_SLEEP_IN_TEST", Severity.WARNING, SourceElementPositioningStrategies.DEFAULT)

    /** A scheduler built by hand where a `Stream.tick` on the graph's backend runs, stops and is tested the same. */
    val SUREFOLD_SCHEDULER_BY_HAND: KtDiagnosticFactory1<String> =
        factory("SUREFOLD_SCHEDULER_BY_HAND", Severity.WARNING, SourceElementPositioningStrategies.DEFAULT)

    /** A secret read as a `String`, which prints itself, where `secret(path)` reads one that does not. Main only. */
    val SUREFOLD_SECRET_AS_STRING: KtDiagnosticFactory1<String> =
        factory("SUREFOLD_SECRET_AS_STRING", Severity.WARNING, SourceElementPositioningStrategies.DEFAULT)

    /** A `run` that parks on its root and never on `awaitShutdown`, so a SIGTERM releases nothing. */
    val SUREFOLD_RUN_NEVER_RETURNS_ON_SHUTDOWN: KtDiagnosticFactory1<String> =
        factory("SUREFOLD_RUN_NEVER_RETURNS_ON_SHUTDOWN", Severity.WARNING, SourceElementPositioningStrategies.DEFAULT)

    /** Something inside a step besides changing `Shared` values: it runs again each time the step does (spec 0210). */
    val SUREFOLD_EFFECT_IN_TRANSACT: KtDiagnosticFactory1<String> =
        factory("SUREFOLD_EFFECT_IN_TRANSACT", Severity.ERROR, SourceElementPositioningStrategies.DEFAULT)

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = Renderers

    private fun factory(
        name: String,
        severity: Severity,
        where: AbstractSourceElementPositioningStrategy,
    ) = KtDiagnosticFactory1<String>(name, severity, where, psiElement, Renderers)
}

/**
 * `PsiElement`, under whichever name it has where this is running.
 *
 * `kotlin-compiler-embeddable` relocates IntelliJ's classes under its own package, and the IDE runs the compiler
 * unrelocated — so a `warning1<PsiElement, _>()` written the usual way compiles to a reference to the relocated name,
 * and the checker dies with `NoClassDefFoundError` the moment the editor reaches it. Resolving the name at runtime is
 * what lets one jar serve both.
 */
private val psiElement: KClass<*> =
    sequenceOf(
            "org.jetbrains.kotlin.com.intellij.psi.PsiElement",
            "com.intellij.psi.PsiElement",
        )
        .firstNotNullOfOrNull(::classNamed)
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
    override val MAP: KtDiagnosticFactoryToRendererMap by
        KtDiagnosticFactoryToRendererMap("Surefold") { map ->
            map.put(SurefoldDiagnostics.SUREFOLD_APP_FOUND, "surefold-app: {0}", CommonRenderers.STRING)
            map.put(SurefoldDiagnostics.SUREFOLD_APP_MISSING, "surefold-app: {0}", CommonRenderers.STRING)
            map.put(SurefoldDiagnostics.SUREFOLD_APP_UNREACHABLE, "surefold-app: {0}", CommonRenderers.STRING)
            map.put(SurefoldDiagnostics.SUREFOLD_APP_DUPLICATE, "surefold-app: {0}", CommonRenderers.STRING)
            map.put(SurefoldDiagnostics.SUREFOLD_APP_CYCLE, "surefold-app: {0}", CommonRenderers.STRING)
            map.put(
                SurefoldDiagnostics.SUREFOLD_APP_UNREAD,
                "surefold-app: this graph was not read here, and is checked by surefoldWiring alone: {0}",
                CommonRenderers.STRING,
            )
            map.put(
                SurefoldDiagnostics.SUREFOLD_SLEEP_IN_TEST,
                // MessageFormat: a single quote opens a quoted run, so an apostrophe is written twice.
                "surefold: {0} waits a fixed time; wait for the condition with " +
                    "surefold-test''s eventually(within) instead",
                CommonRenderers.STRING,
            )
            map.put(
                SurefoldDiagnostics.SUREFOLD_SCHEDULER_BY_HAND,
                "surefold: {0} schedules work by hand; run it as " +
                    "Stream.tick(every) started on the graph''s StreamBackend",
                CommonRenderers.STRING,
            )
            map.put(
                SurefoldDiagnostics.SUREFOLD_SECRET_AS_STRING,
                "surefold: string(\"{0}\") reads a secret as a String that prints itself; read it with secret(\"{0}\")",
                CommonRenderers.STRING,
            )
            map.put(
                SurefoldDiagnostics.SUREFOLD_EFFECT_IN_TRANSACT,
                "surefold: {0}",
                CommonRenderers.STRING,
            )
            map.put(
                SurefoldDiagnostics.SUREFOLD_RUN_NEVER_RETURNS_ON_SHUTDOWN,
                "surefold: run parks on {0}, which waits for the release that only runs after run returns; " +
                    "park on awaitShutdown() so a SIGTERM releases the graph",
                CommonRenderers.STRING,
            )
        }
}
