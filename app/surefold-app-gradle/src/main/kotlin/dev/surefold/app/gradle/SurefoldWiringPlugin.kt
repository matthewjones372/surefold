package dev.surefold.app.gradle

import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.process.CommandLineArgumentProvider

/**
 * Applying this is the whole of what an application does to get its graph checked.
 *
 * The check runs the graph rather than reading the source, which is why it sees an actor node and a module assembled in
 * a conditional — and why it is a task rather than a compiler plugin.
 */
class SurefoldWiringPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val wiring = project.extensions.create("surefoldWiring", SurefoldWiringExtension::class.java)
        wiring.failOn.convention("FAIL")
        wiring.verbose.convention(false)
        wiring.diagrams.convention(project.layout.buildDirectory.dir("reports/surefold"))

        colourReachesTests(project)

        // Registered against the Kotlin plugin rather than at apply time, so the order the two are
        // written in a build script does not decide whether there is a task.
        project.plugins.withId("org.jetbrains.kotlin.jvm") {
            register(project, wiring)
            // The checker in the compiler, which is what puts a fault in the editor. The task below
            // stays the gate: it runs the graph, and sees the parts no static reader can.
            project.plugins.apply(SurefoldCheckerPlugin::class.java)
        }
    }

    /**
     * surefold-test's stories read FORCE_COLOR, NO_COLOR and surefold.test.colour, and a test worker inherits none of
     * them from the shell Gradle was started in (spec 0118). Each is handed on where it is set, as an input, so a
     * change to one runs the tests again rather than serving them from the cache.
     */
    private fun colourReachesTests(project: Project) {
        project.tasks.withType(Test::class.java).configureEach { test ->
            COLOUR_VARIABLES.forEach { name ->
                project.providers.environmentVariable(name).orNull?.let { value ->
                    test.environment(name, value)
                    test.inputs.property(name, value)
                }
            }
            project.providers.gradleProperty(COLOUR_PROPERTY).orNull?.let { value ->
                test.systemProperty(COLOUR_PROPERTY, value)
            }
        }
    }

    private fun register(project: Project, wiring: SurefoldWiringExtension) {
        val main = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
        val java = project.extensions.getByType(JavaPluginExtension::class.java)
        val toolchains = project.extensions.getByType(JavaToolchainService::class.java)

        // Captured as values rather than reaching through the extension inside the provider: a
        // lambda holding the extension itself is what the configuration cache cannot serialise.
        val classes = main.output.classesDirs
        val sources = project.files(main.allSource.srcDirs)
        val diagrams = wiring.diagrams
        val failOn = wiring.failOn

        val check =
            project.tasks.register(TASK, JavaExec::class.java) { exec ->
                exec.group = "verification"
                exec.description = "Checks every surefold-app graph in this project, and renders each one."
                exec.mainClass.set(CHECKER)
                exec.javaLauncher.set(toolchains.launcherFor(java.toolchain))
                exec.classpath = main.runtimeClasspath
                // The report's own bullet is not ASCII, and a forked JVM takes the platform default:
                // on a CI runner that is not UTF-8 every line of it arrives as a question mark.
                exec.defaultCharacterEncoding = "UTF-8"
                exec.inputs.files(main.runtimeClasspath).withPropertyName("runtimeClasspath")
                exec.outputs.dir(diagrams)
                exec.argumentProviders.add(
                    CommandLineArgumentProvider {
                        listOf(
                            classes.joinToString(File.pathSeparator) { it.absolutePath },
                            diagrams.get().asFile.absolutePath,
                            failOn.get(),
                            sources.joinToString(File.pathSeparator) { it.absolutePath },
                        )
                    },
                )
            }

        // Finalising `classes` rather than depending on it: `classes` is what the IDE runs when it
        // builds the project, and what a run configuration builds before it starts anything, so the
        // graph is checked where a compile error would be rather than where a test failure is. A
        // dependency the other way round would be a cycle, since the check needs the classes.
        project.tasks.named("classes") { it.finalizedBy(check) }
        project.tasks.named("check") { it.dependsOn(check) }
    }

    private companion object {
        const val TASK = "surefoldWiring"
        const val CHECKER = "dev.surefold.app.CheckKt"
        const val COLOUR_PROPERTY = "surefold.test.colour"
        val COLOUR_VARIABLES = listOf("FORCE_COLOR", "NO_COLOR")
    }
}
