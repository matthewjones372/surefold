package io.github.matthewjones372.surefold.files

import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldThrow
import java.io.ByteArrayInputStream
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * What every [Files] answers, run against each: `InMemoryFiles` here, MinIO in surefold-files-test. A store that
 * passes it can stand in for another. [files] is a fresh, empty bucket for each test, holding at most 1 MB a file.
 */
abstract class FilesContract : Suite() {

    abstract fun files(): Files

    /** A fresh, empty bucket with no limit of its own, for uploads in parts over S3's 5 MB part size. */
    abstract fun largeFiles(): Files

    init {
        test("a file put is read back, with its size and content type") {
            val files = files()
            val meta = files.put(key("a/b.txt"), bytes("hello"), 5, "text/plain").value()
            meta.size shouldBe 5
            files.get(key("a/b.txt")).value().use { it.stream.readAllBytes().decodeToString() } shouldBe "hello"
            files.head(key("a/b.txt")).value().contentType shouldBe "text/plain"
            files.head(key("a/b.txt")).value().etag shouldBe meta.etag
        }

        test("a key nothing was put at is not found, read or headed") {
            val files = files()
            files.get(key("missing")) shouldBe Either.Left(FileError.NotFound(key("missing")))
            files.head(key("missing")) shouldBe Either.Left(FileError.NotFound(key("missing")))
        }

        test("a put over the store's limit is refused as too large") {
            val files = files()
            val over = files.maxSize + 1
            files.put(key("big"), ByteArrayInputStream(ByteArray(0)), over, "application/octet-stream") shouldBe
                Either.Left(FileError.TooLarge(key("big"), over, files.maxSize))
        }

        test("a put replaces what was there, and changes its etag") {
            val files = files()
            val first = files.put(key("x"), bytes("one"), 3, "text/plain").value()
            val second = files.put(key("x"), bytes("three"), 5, "text/plain").value()
            (first.etag == second.etag) shouldBe false
            files.get(key("x")).value().use { it.stream.readAllBytes().decodeToString() } shouldBe "three"
        }

        test("a deleted file is gone, and deleting it again succeeds") {
            val files = files()
            files.put(key("x"), bytes("one"), 3, "text/plain").value()
            files.delete(key("x")) shouldBe Either.Right(Unit)
            files.head(key("x")) shouldBe Either.Left(FileError.NotFound(key("x")))
            files.delete(key("x")) shouldBe Either.Right(Unit)
        }

        test("a listing is in key order, under its prefix only, a page at a time") {
            val files = files()
            listOf("t/a/3", "t/a/1", "t/a/2", "t/b/1", "u/1").forEach { name ->
                files.put(key(name), bytes("x"), 1, "text/plain")
            }
            val first = files.list("t/a/", limit = 2).value()
            first.files.map { it.key.value } shouldContainExactly listOf("t/a/1", "t/a/2")
            val second = files.list("t/a/", after = first.next, limit = 2).value()
            second.files.map { it.key.value } shouldContainExactly listOf("t/a/3")
            second.next shouldBe null
            files.list("v/").value().files shouldBe emptyList()
        }

        test("an upload in three parts reads back whole, in part order") {
            val files = largeFiles()
            val parts = listOf(FIVE_MB, FIVE_MB, 2 * MB).mapIndexed { index, size ->
                ByteArray(size) { (index + 1).toByte() }
            }
            val upload = files.multipart(key("big.bin"), "application/octet-stream").value()
            // Sent out of order, as parts sent from several threads arrive.
            listOf(2, 0, 1).forEach { i ->
                upload.part(i + 1, ByteArrayInputStream(parts[i]), parts[i].size.toLong()).value()
            }
            upload.complete().value().size shouldBe (12 * MB).toLong()
            val read = files.get(key("big.bin")).value().use { it.stream.readAllBytes() }
            read.contentEquals(parts.fold(ByteArray(0)) { all, part -> all + part }) shouldBe true
        }

        test("an aborted upload leaves nothing at its key") {
            val files = largeFiles()
            val upload = files.multipart(key("gone.bin"), "application/octet-stream").value()
            upload.part(1, bytes("x"), 1).value()
            upload.abort().value()
            files.head(key("gone.bin")) shouldBe Either.Left(FileError.NotFound(key("gone.bin")))
        }

        test("a presigned PUT over the store's limit is refused, and one for longer than a week is not made") {
            val files = files()
            files.presignPut(key("x"), 1.minutes, "text/plain", files.maxSize + 1) shouldBe
                Either.Left(FileError.TooLarge(key("x"), files.maxSize + 1, files.maxSize))
            shouldThrow<IllegalArgumentException> { files.presignGet(key("x"), 8.days) }
        }

        test("a copy is a second file with the same content, and copying a missing file is not found") {
            val files = files()
            files.put(key("from"), bytes("hello"), 5, "text/plain").value()
            files.copy(key("from"), key("to")).value().size shouldBe 5
            files.get(key("to")).value().use { it.stream.readAllBytes().decodeToString() } shouldBe "hello"
            files.copy(key("nothing"), key("to2")) shouldBe Either.Left(FileError.NotFound(key("nothing")))
        }
    }

    protected fun key(value: String): FileKey = FileKey.of(value).value()

    private companion object {
        const val MB = 1024 * 1024
        const val FIVE_MB = 5 * MB
    }

    private fun bytes(text: String) = ByteArrayInputStream(text.encodeToByteArray())

    /** The right of [this], or a failed check naming the left. */
    protected fun <E, A> Either<E, A>.value(): A = when (this) {
        is Either.Right -> value
        is Either.Left -> throw AssertionError("expected a value, but was $value")
    }
}
