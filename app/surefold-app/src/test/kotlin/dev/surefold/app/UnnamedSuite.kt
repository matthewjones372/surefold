package dev.surefold.app

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldNotBeNull
import java.io.File
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * Names this public repository never prints: the private services built on it. The names are held as SHA-256 digests
 * of their lower-case spelling, so this file does not print them either; a word in any tracked text file whose digest
 * is one of them fails the build.
 */
object UnnamedSuite : Suite() {
    private val unnamed = setOf("dc5d2b2b022126bf2c22d2095324c43e3947ffa8a58711ba1b98ffd718907dc8")

    private val word = Regex("[a-z0-9]+")

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** The repository's tracked files, as git lists them. */
    private fun tracked(root: File): List<File> {
        val listing = ProcessBuilder("git", "ls-files", "-z").directory(root).start()
        val names = listing.inputStream.bufferedReader().readText().split('\u0000').filter { it.isNotEmpty() }
        listing.waitFor() shouldBe 0
        return names.map { File(root, it) }.filter { it.isFile }
    }

    /** [file]'s text, or null for a file that is not UTF-8 text: an image, a jar. */
    private fun textOf(file: File): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(file.readBytes()))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    init {
        test("no tracked file names a private service built on Surefold") {
            System.getProperty("surefold.app.repoRoot").shouldNotBeNull { root ->
                val named = tracked(File(root)).filter { file ->
                    val text = textOf(file) ?: return@filter false
                    word.findAll(text.lowercase()).map { it.value }.toSet().any { digest(it) in unnamed }
                }
                named.map { it.relativeTo(File(root)).path } shouldBe emptyList()
            }
        }
    }
}
