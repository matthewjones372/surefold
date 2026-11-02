package io.github.matthewjones372.surefold.files.test

import dev.surefold.fail.Either
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeLessThan
import dev.surefold.policy
import io.github.matthewjones372.surefold.files.FileError
import io.github.matthewjones372.surefold.files.Files
import io.github.matthewjones372.surefold.files.FilesConfig
import io.github.matthewjones372.surefold.files.FilesContract
import io.github.matthewjones372.surefold.files.Presigned
import io.github.matthewjones372.surefold.files.S3Files
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** S3Files on MinIO keeps the contract the fake keeps, and an unreachable store answers within its deadline. */
object MinioFilesSuite : FilesContract() {
    override fun files(): Files = Minio.files(maxSize = 1_000_000)

    override fun largeFiles(): Files = Minio.files()

    private val http = HttpClient.newHttpClient()

    /** Sends [presigned] as a browser would, with its signed headers and [body]; answers the status. */
    private fun send(presigned: Presigned, body: ByteArray? = null): Pair<Int, String> {
        val publisher = body?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(presigned.url).method(presigned.method, publisher)
        // The JDK's client sets Content-Length itself, from the body, and refuses it as a header.
        presigned.headers.filterKeys { !it.equals("content-length", ignoreCase = true) }
            .forEach { (name, value) -> request.header(name, value) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).let { it.statusCode() to it.body() }
    }

    init {
        test("a store nobody answers is unavailable, within the policy's deadline") {
            val nobody = ServerSocket(0).use { it.localPort }
            val files = S3Files.of(
                FilesConfig("b", "us-east-1", "k", "s", endpoint = URI("http://127.0.0.1:$nobody"), pathStyle = true),
                policy("files") { deadline(2.seconds) },
            )
            val started = TimeSource.Monotonic.markNow()
            val answer = files.put(key("x"), ByteArrayInputStream(byteArrayOf(1)), 1, "text/plain")
            started.elapsedNow() shouldBeLessThan 5.seconds
            ((answer as Either.Left).value is FileError.Unavailable) shouldBe true
        }

        test("a presigned PUT of the size it was signed for is stored, and one of another size is refused") {
            val files = Minio.files(maxSize = 1_000_000)
            val signed = (files.presignPut(key("upload.txt"), 5.minutes, "text/plain", 5) as Either.Right).value
            signed.headers["content-type"] shouldBe "text/plain"
            send(signed, "toolong".toByteArray()).first shouldBe 403
            (files.head(key("upload.txt")) is Either.Left) shouldBe true
            send(signed, "hello".toByteArray()).first shouldBe 200
            ((files.head(key("upload.txt")) as Either.Right).value.size) shouldBe 5L
        }

        test("a presigned GET reads the file without credentials") {
            val files = Minio.files(maxSize = 1_000_000)
            files.put(key("a.txt"), ByteArrayInputStream("hello".toByteArray()), 5, "text/plain")
            val signed = (files.presignGet(key("a.txt"), 5.minutes) as Either.Right).value
            send(signed) shouldBe (200 to "hello")
        }

        test("a wrong secret is access denied") {
            val config = Minio.config()
            val files = S3Files.of(config.copy(secretKey = "wrong"))
            val answer = files.head(key("x"))
            ((answer as Either.Left).value is FileError.AccessDenied) shouldBe true
        }
    }
}
