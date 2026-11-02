package dev.surefold.junit

import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs [block] with a new, empty directory of its own, and deletes the directory and everything in it afterwards,
 * whether [block] returned or threw: what Jupiter's `@TempDir` gives a test. Nothing is shared between calls, so
 * tests running together each get their own.
 */
fun <A> withTempDirectory(prefix: String = "surefold-junit", block: (Path) -> A): A {
    val directory = Files.createTempDirectory(prefix)
    try {
        return block(directory)
    } finally {
        directory.toFile().deleteRecursively()
    }
}
