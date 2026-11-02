package io.github.matthewjones372.surefold.tenancy.crypto.aws

import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.ensure
import io.github.matthewjones372.surefold.tenancy.crypto.KeyOperation
import io.github.matthewjones372.surefold.tenancy.crypto.KeyOperationFailure

internal class KmsPage<A>(val entries: List<A>, val present: Boolean, val truncated: Boolean?, val next: String?)

internal fun <A> kmsPages(
    operation: KeyOperation,
    read: (String?) -> KmsPage<A>,
): Either<KeyOperationFailure, List<A>> = attempt {
    var marker: String? = null
    var seen = emptySet<String>()
    var entries = emptyList<A>()
    do {
        val page = read(marker)
        ensure(page.present && page.truncated != null) { incomplete(operation) }
        entries = entries + page.entries
        val next = page.next
        if (page.truncated == true) {
            ensure(
                !next.isNullOrBlank() && next.length <= MAX_MARKER_LENGTH && next.all {
                    it in ' '..'ÿ'
                } && next !in seen,
            ) { incomplete(operation) }
            seen = seen + next
            marker = next
        } else {
            ensure(next == null) { incomplete(operation) }
            marker = null
        }
    } while (marker != null)
    entries
}

private const val MAX_MARKER_LENGTH = 1024
