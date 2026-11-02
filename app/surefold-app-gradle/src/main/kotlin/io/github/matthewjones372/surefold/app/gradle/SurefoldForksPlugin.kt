package io.github.matthewjones372.surefold.app.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.util.Properties

/**
 * `dev.surefold.compiler`: surefold-compiler's checkers in every Kotlin compilation (spec 0041),
 * so a `Deferred` that outlives its scope fails to compile. A plugin of its own, apart from the wiring plugin, so a
 * service without surefold-app takes it alone; it lives in this jar beside that one, which already publishes plugins.
 */
class SurefoldForksPlugin : KotlinCompilerPluginSupportPlugin {

    override fun apply(target: Project) = Unit

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(groupId = forks("group"), artifactId = forks("forksArtifact"), version = forks("version"))

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> =
        kotlinCompilation.target.project.provider { emptyList() }

    private companion object {
        const val PLUGIN_ID = "dev.surefold.compiler"

        /** Written into this jar by its own build, so the coordinates cannot drift from the release. */
        private val coordinates: Properties by lazy {
            Properties().apply {
                SurefoldForksPlugin::class.java.getResourceAsStream("/checker.properties")
                    ?.use { load(it) }
                    ?: error("surefold-app-gradle was built without checker.properties; see its build script")
            }
        }

        fun forks(key: String): String = coordinates.getProperty(key) ?: error("checker.properties has no $key")
    }
}
