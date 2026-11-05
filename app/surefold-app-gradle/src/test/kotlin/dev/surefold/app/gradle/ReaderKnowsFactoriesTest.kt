package dev.surefold.app.gradle

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.util.jar.JarFile
import org.junit.jupiter.api.Test

/**
 * A module on `surefoldAppUnderTest` whose factory the checker's reader does not know makes the checker give up on
 * every graph using it, and giving up is silent. So each public function such a module ships that returns a `Module`
 * must be one the reader names.
 */
class ReaderKnowsFactoriesTest {

    @Test
    fun `the reader knows every factory the modules under test ship`() {
        val jars = System.getProperty("surefold.app.underTest").shouldNotBeNull().split(File.pathSeparator).map(::File)
        val reader = File(System.getProperty("surefold.checker.reader").shouldNotBeNull()).readText()
        val known =
            Regex("""extension\("(\w+)", "(\w+)"\)""")
                .findAll(reader)
                .map { "${it.groupValues[1]}.${it.groupValues[2]}" }
                .toSet()

        withClue("each needs an extension(\"module\", \"name\") in surefold-app-compiler's Reader.kt") {
            factories(jars) - known shouldBe emptySet()
        }
    }

    /** `module.name` for each public static method returning a `Module`, in a package under surefold-app's own. */
    private fun factories(jars: List<File>): Set<String> =
        URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), null).use { loader -> factories(jars, loader) }

    private fun factories(jars: List<File>, loader: ClassLoader): Set<String> {
        val module = loader.loadClass("$APP.Module")
        return jars
            .flatMap { jar ->
                JarFile(jar)
                    .use { file -> file.entries().toList().map { it.name } }
                    .filter { it.startsWith(APP.replace('.', '/') + "/") && it.endsWith(".class") }
                    .map { it.removeSuffix(".class").replace('/', '.') }
                    .filter { it.substringBeforeLast('.') != APP }
            }
            .flatMap { name ->
                loader
                    .loadClass(name)
                    .declaredMethods
                    .filter { Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) }
                    .filter { it.returnType == module && '$' !in it.name }
                    // A value-class parameter mangles the JVM name: `actor-8Mi8wO0` is `actor`.
                    .map { "${name.removePrefix("$APP.").substringBefore('.')}.${it.name.substringBefore('-')}" }
            }
            .toSet()
    }

    private companion object {
        const val APP = "dev.surefold.app"
    }
}
