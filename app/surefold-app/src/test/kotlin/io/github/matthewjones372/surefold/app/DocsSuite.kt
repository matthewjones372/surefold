package io.github.matthewjones372.surefold.app

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.withClue
import java.io.File

/** What the fence-compiling tests do not check: the order of an example's imports, and the coordinates it names. */
object DocsSuite : Suite() {
    private fun pages(root: File, then: (List<File>) -> Unit) =
        File(root, "docs").listFiles { file -> file.extension == "md" }.shouldNotBeNull { docs ->
            then(listOf(File(root, "README.md")) + docs.sortedBy { it.name })
        }

    private fun root(then: (File) -> Unit) =
        System.getProperty("surefold.app.repoRoot").shouldNotBeNull { root -> then(File(root)) }

    init {
        test("every import block in a Kotlin fence is in ktlint's order") {
            root { root ->
                pages(root) { pages ->
                    val unordered = pages.flatMap { page ->
                        importBlocks(page.readText()).filter { it != it.sortedWith(ktlintImportOrder) }
                            .map { "${page.name}: ${it.first()}" }
                    }
                    unordered shouldBe emptyList()
                }
            }
        }

        test("every coordinate names a published module, at the released version or a snapshot") {
            root { root ->
                pages(root) { pages ->
                    System.getProperty("surefold.app.publishedModules").shouldNotBeNull { modules ->
                        val published = modules.split(",").toSet()
                        val modulesPage = File(root, "docs/modules.md").readText()
                        val released = installedVersion(modulesPage)
                        withClue("docs/modules.md: the install block and the snapshot paragraph") {
                            taggedVersion(modulesPage) shouldBe released
                        }
                        val wrong = pages.flatMap { page ->
                            COORDINATE.findAll(page.readText()).map { it.groupValues }
                                .filterNot { (_, module, version) ->
                                    module in published && (version == released || version.endsWith("-SNAPSHOT"))
                                }
                                .map { "${page.name}: ${it[0]}" }
                        }
                        withClue("released version $released, as docs/modules.md says") {
                            wrong shouldBe emptyList()
                        }
                    }
                }
            }
        }
    }
}

private val COORDINATE = Regex("""io\.github\.matthewjones372:([A-Za-z0-9._-]+):([A-Za-z0-9._-]+)""")

/** The version docs/modules.md installs. */
internal fun installedVersion(modules: String): String =
    COORDINATE.findAll(modules).map { it.groupValues[2] }.toSet().single()

/** The version docs/modules.md's snapshot paragraph names as the last tag, which must be the installed one too. */
internal fun taggedVersion(modules: String): String? = Regex("""after `v([^`]+)`""").find(modules)?.groupValues?.get(1)

/** Each run of consecutive `import` lines inside a ```kotlin fence. */
internal fun importBlocks(page: String): List<List<String>> =
    Regex("""```kotlin\n(.*?)```""", RegexOption.DOT_MATCHES_ALL).findAll(page).flatMap { fence ->
        fence.groupValues[1].lines().fold(listOf(emptyList<String>())) { blocks, line ->
            when {
                line.startsWith("import ") -> blocks.dropLast(1) + listOf(blocks.last() + line)
                blocks.last().isEmpty() -> blocks
                else -> blocks + listOf(emptyList())
            }
        }.filter { it.isNotEmpty() }
    }.toList()

/** ktlint's `intellij_idea` layout, `*,java.**,javax.**,kotlin.**,^`: each group in plain string order. */
internal val ktlintImportOrder: Comparator<String> = compareBy<String> { line ->
    val path = line.removePrefix("import ")
    when {
        " as " in path -> 4
        path.startsWith("java.") -> 1
        path.startsWith("javax.") -> 2
        path.startsWith("kotlin.") -> 3
        else -> 0
    }
}.thenBy { it.removePrefix("import ").substringBefore(" as ").replace("`", "") }
