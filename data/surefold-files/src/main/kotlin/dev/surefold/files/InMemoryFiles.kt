package dev.surefold.files

import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentSkipListMap
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** A [Files] in memory, for tests: the same answers and failures as a store, and nothing kept past the process. */
class InMemoryFiles(override val maxSize: Long = Long.MAX_VALUE) : Files {

    private class Stored(val bytes: ByteArray, val meta: FileMeta)

    private val stored = ConcurrentSkipListMap<String, Stored>()

    override fun put(key: FileKey, content: InputStream, size: Long, contentType: String): Either<FileError, FileMeta> {
        if (size > maxSize) return FileError.TooLarge(key, size, maxSize).left()
        val bytes = content.readNBytes(size.toInt())
        require(bytes.size.toLong() == size && content.read() == -1) {
            "put of $key said $size bytes, and its stream did not hold exactly that."
        }
        val meta = FileMeta(key, size, contentType, etag(bytes), clock.get().now())
        stored[key.value] = Stored(bytes, meta)
        return meta.right()
    }

    override fun get(key: FileKey): Either<FileError, FileContent> =
        stored[key.value]?.let { FileContent(it.meta, ByteArrayInputStream(it.bytes)).right() }
            ?: FileError.NotFound(key).left()

    override fun head(key: FileKey): Either<FileError, FileMeta> =
        stored[key.value]?.meta?.right() ?: FileError.NotFound(key).left()

    override fun delete(key: FileKey): Either<FileError, Unit> {
        stored.remove(key.value)
        return Unit.right()
    }

    override fun list(prefix: String, after: String?, limit: Int): Either<FileError, FilePage> {
        require(limit >= 1) { "A listing takes at least one file, not $limit." }
        val from = if (after == null) stored.tailMap(prefix, true) else stored.tailMap(maxOf(prefix, after), false)
        val matching = from.entries.asSequence().takeWhile { it.key.startsWith(prefix) }.take(limit + 1).toList()
        val page = matching.take(limit).map { it.value.meta.copy(contentType = null) }
        return FilePage(page, if (matching.size > limit) page.last().key.value else null).right()
    }

    override fun copy(from: FileKey, to: FileKey): Either<FileError, FileMeta> {
        val source = stored[from.value] ?: return FileError.NotFound(from).left()
        val meta = source.meta.copy(key = to, modified = clock.get().now())
        stored[to.value] = Stored(source.bytes, meta)
        return meta.right()
    }

    override fun presignPut(
        key: FileKey,
        expiresIn: Duration,
        contentType: String,
        size: Long,
    ): Either<FileError, Presigned> {
        require(expiresIn > Duration.ZERO && expiresIn <= LONGEST_PRESIGN) { "A presigned URL lasts up to 7 days." }
        if (size > maxSize) return FileError.TooLarge(key, size, maxSize).left()
        val headers = mapOf("content-type" to contentType, "content-length" to "$size")
        return Presigned(URI("memory:///$key"), "PUT", headers, clock.get().now() + expiresIn.toJavaDuration()).right()
    }

    override fun presignGet(key: FileKey, expiresIn: Duration): Either<FileError, Presigned> {
        require(expiresIn > Duration.ZERO && expiresIn <= LONGEST_PRESIGN) { "A presigned URL lasts up to 7 days." }
        val expiresAt = clock.get().now() + expiresIn.toJavaDuration()
        return Presigned(URI("memory:///$key"), "GET", emptyMap(), expiresAt).right()
    }

    override fun multipart(key: FileKey, contentType: String): Either<FileError, MultipartUpload> =
        Upload(key, contentType).right()

    private inner class Upload(override val key: FileKey, private val contentType: String) : MultipartUpload {
        private val parts = ConcurrentSkipListMap<Int, ByteArray>()

        override fun part(number: Int, content: InputStream, size: Long): Either<FileError, Unit> {
            require(number >= 1) { "Parts are numbered from 1, not $number." }
            parts[number] = content.readNBytes(size.toInt())
            return Unit.right()
        }

        override fun complete(): Either<FileError, FileMeta> {
            val whole = parts.values.fold(ByteArray(0)) { joined, part -> joined + part }
            return put(key, ByteArrayInputStream(whole), whole.size.toLong(), contentType)
        }

        override fun abort(): Either<FileError, Unit> {
            parts.clear()
            return Unit.right()
        }
    }

    private fun etag(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes))
}
