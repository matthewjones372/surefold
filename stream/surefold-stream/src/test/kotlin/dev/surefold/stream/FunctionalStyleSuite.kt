package dev.surefold.stream

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.shouldNotBeEmpty
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.withClue
import java.io.File

/**
 * Where mutable state is allowed to live, stated as a test.
 *
 * The list is nearly empty: a `Stream` is a description handed to Pekko and Pekko does the collecting, so nothing here
 * accumulates a result. A file that needs a mutable collection has to be argued for here, in writing, before the build
 * goes green again.
 *
 * Which sources it judges is not decided here: the build hands them over and declares the same directories as inputs of
 * the task that runs this test. See `surefold-stream/build.gradle.kts`.
 */
object FunctionalStyleSuite : Suite() {
    // A description holds nothing that grows. What a backend keeps while it runs is that backend's.
    private val builders = emptyMap<String, String>()

    private val accumulators =
        Regex(
            """\b(mutableListOf|mutableMapOf|mutableSetOf|newKeySet""" +
                """|LinkedHashMap|LinkedHashSet|IdentityHashMap|ArrayList|HashMap|HashSet)\s*[(<]""",
        )

    /** Absent means the build's wiring is gone, which is the failure this test cannot survive. */
    private fun handedOver(name: String, then: (String) -> Unit) {
        val value = System.getProperty(name)
        return if (value == null) {
            withClue("the build must pass -D$name; see surefold-stream/build.gradle.kts") { value.shouldNotBeNull() }
        } else {
            then(value)
        }
    }

    private fun repoRoot(then: (File) -> Unit) = handedOver("surefold.stream.repoRoot") { then(File(it)) }

    private fun sourceRoots(then: (List<File>) -> Unit) =
        handedOver("surefold.stream.sources") { value ->
            then(value.split(File.pathSeparator).filter { it.isNotBlank() }.map(::File))
        }

    private fun mainSources(roots: List<File>): List<File> = roots.flatMap {
        it.walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.toList()
    }

    init {
        test("the build hands this test the sources it judges") {
            sourceRoots { roots ->
                repoRoot { root ->
                    withClue("the build named no source roots at all") { roots.shouldNotBeEmpty() }

                    val missing = roots.filterNot { it.isDirectory }
                    withClue("the build named source roots that are not there: $missing") {
                        missing.shouldBeEmpty()
                    }

                    val outside = roots.filterNot { it.absoluteFile.startsWith(root.absoluteFile) }
                    withClue("a source root sits outside $root, so the paths below cannot be keys: $outside") {
                        outside.shouldBeEmpty()
                    }

                    withClue("no sources were handed over; the gate is judging nothing") {
                        mainSources(roots).shouldNotBeEmpty()
                    }
                }
            }
        }

        test("mutable collections are built only where a builder was meant to be") {
            sourceRoots { roots ->
                repoRoot { root ->
                    val found =
                        mainSources(roots)
                            .filter { accumulators.containsMatchIn(it.readText()) }
                            .map { it.relativeTo(root).path }
                            .toSortedSet()

                    val unexpected = found - builders.keys
                    val stale = builders.keys - found
                    withClue("a mutable accumulator appeared outside a builder: $unexpected") {
                        unexpected.shouldBeEmpty()
                    }
                    withClue("these no longer accumulate; drop them from the list: $stale") {
                        stale.shouldBeEmpty()
                    }
                }
            }
        }

        test("nothing in the library holds a var") {
            sourceRoots { roots ->
                repoRoot { root ->
                    val vars =
                        mainSources(roots)
                            .filter { Regex("""(^|\s)var\s""").containsMatchIn(it.readText()) }
                            .map { it.relativeTo(root).path }

                    withClue("a `var` appeared in the library: $vars") { vars.shouldBeEmpty() }
                }
            }
        }
    }
}
