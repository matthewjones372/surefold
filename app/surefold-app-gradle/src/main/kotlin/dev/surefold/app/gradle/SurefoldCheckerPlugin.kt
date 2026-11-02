package dev.surefold.app.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.util.Properties

/**
 * The half of the wiring plugin that runs inside the Kotlin compiler.
 *
 * It is a separate `Plugin` only because Gradle finds a compiler plugin by this interface; a project
 * applies `SurefoldWiringPlugin`, which applies this. The checker artifact is resolved rather than
 * embedded, because it runs in the compiler's classloader and the task in this jar runs in
 * Gradle's — one jar for both would put `gradleApi()` in front of the compiler.
 */
class SurefoldCheckerPlugin : KotlinCompilerPluginSupportPlugin {

    override fun apply(target: Project) = Unit

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(groupId = checker("group"), artifactId = checker("artifact"), version = checker("version"))

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val verbose = kotlinCompilation.target.project.extensions
            .getByType(SurefoldWiringExtension::class.java).verbose
        // Every compilation but main is tests: test, testFixtures and any source set a build adds for them.
        val test = kotlinCompilation.compilationName != KotlinCompilation.MAIN_COMPILATION_NAME
        return kotlinCompilation.target.project.provider {
            listOf(SubpluginOption("verbose", verbose.get().toString()), SubpluginOption("test", test.toString()))
        }
    }

    private companion object {
        const val PLUGIN_ID = "dev.surefold.app"

        /** Written into this jar by its own build, so the coordinates cannot drift from the release. */
        private val coordinates: Properties by lazy {
            Properties().apply {
                SurefoldCheckerPlugin::class.java.getResourceAsStream("/checker.properties")
                    ?.use { load(it) }
                    ?: error("surefold-app-gradle was built without checker.properties; see its build script")
            }
        }

        fun checker(key: String): String =
            coordinates.getProperty(key) ?: error("checker.properties has no $key")
    }
}
