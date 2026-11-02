package io.github.matthewjones372.surefold.files.test

import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.files.FileError
import io.github.matthewjones372.surefold.files.FileKey
import io.github.matthewjones372.surefold.files.InMemoryFiles
import java.io.ByteArrayInputStream
import java.io.File
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** docs/files.md's example is GuideExample.kt, and it runs on MinIO and on the fake alike. */
object FilesGuideSuite : Suite() {
    private val marked = Regex("""<!-- files -->\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)

    init {
        test("the guide's example is GuideExample.kt from its imports down") {
            val root = System.getProperty("surefold.files.test.repoRoot")
            expect(root != null) { "the build must pass -Dsurefold.files.test.repoRoot; see its build file" }
            val fences = marked.findAll(File(root!!, "docs/files.md").readText()).map { it.groupValues[1] }.toList()
            fences.size shouldBe 1
            val source = File(root, EXAMPLE).readText()
            source.substring(source.indexOf("import ")).trim() shouldBe fences.single().trim()
        }

        test("a ticket stored on MinIO is linked to, and the link reads it without credentials") {
            val tickets = Tickets(Minio.files())
            (tickets.store("acme", "b-1", ByteArrayInputStream("%PDF".toByteArray()), 4) is Either.Right) shouldBe true
            val link = (tickets.link("acme", "b-1") as Either.Right).value
            val read = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(link.url).build(), HttpResponse.BodyHandlers.ofString())
            read.body() shouldBe "%PDF"
            val elsewhere = (FileKey.of("tenants/globex/tickets/b-1.pdf") as Either.Right).value
            tickets.link("globex", "b-1") shouldBe Either.Left(FileError.NotFound(elsewhere))
        }

        test("a booking id that would escape the tenant's folder is refused before anything is stored") {
            val tickets = Tickets(InMemoryFiles())
            (tickets.store("acme", "../../globex/x", ByteArrayInputStream(byteArrayOf()), 0) as Either.Left).value
                .let { it is FileError.InvalidKey } shouldBe true
        }
    }

    private const val EXAMPLE =
        "data/surefold-files-test/src/test/kotlin/io/github/matthewjones372/surefold/files/test/GuideExample.kt"
}
