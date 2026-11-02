package dev.surefold.files

import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right

/**
 * Where a file lives in its bucket, checked once: not empty, at most 1,024 bytes of UTF-8 as S3 allows, no leading
 * `/`, no `.` or `..` segment that a path-joining client would resolve away, and no control characters.
 */
@JvmInline
value class FileKey private constructor(val value: String) {

    /** The last segment, `c.pdf` for `a/b/c.pdf`. */
    val name: String get() = value.substringAfterLast('/')

    override fun toString(): String = value

    companion object {
        private const val MOST_BYTES = 1_024

        fun of(value: String): Either<FileError.InvalidKey, FileKey> {
            val why = when {
                value.isEmpty() -> "it is empty"
                value.toByteArray(Charsets.UTF_8).size > MOST_BYTES -> "it is over $MOST_BYTES bytes"
                value.startsWith('/') -> "it starts with '/'"
                value.split('/').any { it == "." || it == ".." } -> "it has a '.' or '..' segment"
                value.any { it.isISOControl() } -> "it has a control character"
                else -> null
            }
            return if (why == null) FileKey(value).right() else FileError.InvalidKey(value, why).left()
        }
    }
}

/**
 * The keys of one tenant, all under `tenants/<tenant>/`, so a listing or a policy on the prefix sees only that
 * tenant's files. The tenant must be one path segment.
 */
class TenantPrefix(val tenant: String) {
    init {
        require(tenant.isNotEmpty() && '/' !in tenant && tenant != "." && tenant != "..") {
            "A tenant is one path segment, not \"$tenant\"."
        }
    }

    val prefix: String get() = "tenants/$tenant/"

    fun key(path: String): Either<FileError.InvalidKey, FileKey> = FileKey.of(prefix + path.removePrefix("/"))

    /** Whether [key] is one of this tenant's. */
    operator fun contains(key: FileKey): Boolean = key.value.startsWith(prefix)
}
