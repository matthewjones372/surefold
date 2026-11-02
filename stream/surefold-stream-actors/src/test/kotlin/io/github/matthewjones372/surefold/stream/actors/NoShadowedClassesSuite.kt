package io.github.matthewjones372.surefold.stream.actors

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.withClue
import io.github.matthewjones372.surefold.stream.Actors
import java.io.File

/**
 * This module shares its package with surefold-stream and surefold-stream-forks, where a private class or a file's
 * facade is still a class of the package: two of one name and the JVM loads whichever it finds first, which is how a
 * merge on Forks once reached this module's class. Every class compiled here must be the only one of its name.
 */
object NoShadowedClassesSuite : Suite() {
    init {
        test("no class of this module has the name of a class in another module of its package") {
            val here = File(Actors::class.java.protectionDomain.codeSource.location.toURI())
            val packagePath = Actors::class.java.packageName.replace('.', '/')
            val loader = Actors::class.java.classLoader

            val shadowed = File(here, packagePath).listFiles().orEmpty()
                .map { it.name }
                .filter { it.endsWith(".class") }
                .filter { loader.getResources("$packagePath/$it").toList().size > 1 }

            withClue("classes that another module of the package also has") { shadowed.shouldBeEmpty() }
        }
    }
}
