package io.github.matthewjones372.surefold.app

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldNotBeNull
import java.io.File

/**
 * Every spec has a number no other spec in its folder has, and its heading names that number. Specs are drafted in
 * parallel, and two once merged as 0216 eleven minutes apart; this fails the second.
 */
object SpecsSuite : Suite() {
    private val numbered = Regex("""^(\d{4})-.+\.md$""")

    private fun folders(then: (List<File>) -> Unit) =
        System.getProperty("surefold.app.repoRoot").shouldNotBeNull { root ->
            val specs = File(root, "specs")
            then(listOf(specs) + specs.listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.name })
        }

    private fun specsIn(folder: File): List<File> =
        folder.listFiles { file -> numbered.matches(file.name) }.orEmpty().sortedBy { it.name }

    private fun numberOf(spec: File): String = numbered.find(spec.name)!!.groupValues[1]

    init {
        test("no two specs in a folder share a number") {
            folders { folders ->
                val shared = folders.flatMap { folder ->
                    specsIn(folder).groupBy(::numberOf).filterValues { it.size > 1 }
                        .map { (number, specs) -> "${folder.name}/$number: ${specs.joinToString { it.name }}" }
                }
                shared shouldBe emptyList()
            }
        }

        test("every spec's heading names its own number") {
            folders { folders ->
                val misnamed = folders.flatMap { folder ->
                    specsIn(folder).filterNot { spec ->
                        Regex("""^# ${numberOf(spec)}\D""").containsMatchIn(spec.useLines { it.first() })
                    }.map { "${folder.name}/${it.name}" }
                }
                misnamed shouldBe emptyList()
            }
        }
    }
}
