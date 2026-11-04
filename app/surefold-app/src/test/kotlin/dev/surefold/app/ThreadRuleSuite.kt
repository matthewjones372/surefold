package dev.surefold.app

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.withClue
import java.io.File

/** A file the thread rule excuses, and the comment above it that says why. */
private data class Excused(val glob: String, val why: String)

/**
 * The files detekt's `ForbiddenMethodCall/threads` lets start a thread by hand (spec 0309): each one says why, and
 * still does, so the list only shrinks as files move onto Surefold's own primitives.
 */
object ThreadRuleSuite : Suite() {
    // `Thread(…)` and `Thread { … }` alike: a constructor called with a trailing lambda has no parenthesis.
    private val starts = Regex("""Thread\.ofVirtual\(|Thread\.ofPlatform\(|(?<![\w.])Thread\s*[({]""")

    private fun repoRoot(then: (String) -> Unit) =
        System.getProperty("surefold.app.repoRoot")?.let(then)
            ?: withClue("the build must pass -Dsurefold.app.repoRoot; see surefold-app/build.gradle.kts") {
                null.shouldNotBeNull()
            }

    /** The rule's excludes that name one file, each with the comment lines just above it. */
    private fun excused(root: String): List<Excused> {
        val lines = File(root, "config/detekt/detekt.yml").readLines()
        val start = lines.indexOfFirst { it.trim() == "ForbiddenMethodCall/threads:" }
        withClue("config/detekt/detekt.yml must hold ForbiddenMethodCall/threads") { (start >= 0) shouldBe true }
        val rule = lines.drop(start + 1).takeWhile { it.isBlank() || it.startsWith("    ") }
        val excludes = rule.dropWhile { it.trim() != "excludes:" }.drop(1)
        return excludes.mapIndexedNotNull { i, line ->
            val glob = line.trim().removePrefix("- ").trim('\'')
            if (!line.trim().startsWith("- ") || glob.endsWith("/**")) return@mapIndexedNotNull null
            val why = excludes.take(i).reversed().takeWhile { it.trim().startsWith("#") }
            Excused(glob, why.joinToString(" ") { it.trim().removePrefix("#").trim() })
        }
    }

    private fun fileOf(root: String, excused: Excused) = File(root, excused.glob.removePrefix("**/"))

    private fun startsAThread(file: File) = file.isFile && starts in file.readText()

    init {
        test("the thread rule excuses some files, so these checks judge something") {
            repoRoot { root -> (excused(root).size > 5) shouldBe true }
        }

        test("every file the thread rule excuses says why, in a comment above it") {
            repoRoot { root ->
                val unexplained = excused(root).filter { it.why.isBlank() }.map { it.glob }
                unexplained shouldBe emptyList()
            }
        }

        test("every file the thread rule excuses is there and still starts a thread, so a fixed one leaves the list") {
            repoRoot { root ->
                val stale = excused(root).filterNot { startsAThread(fileOf(root, it)) }
                withClue("excused in ForbiddenMethodCall/threads, but no longer starting a thread there") {
                    stale.map { it.glob } shouldBe emptyList()
                }
            }
        }
    }
}
