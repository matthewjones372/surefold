package dev.surefold.tenancy.crypto

import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.left
import dev.surefold.fail.right

internal typealias InMemoryKeyReply<A> = Either<KeyOperationFailure, A>
internal typealias InMemoryChange<A> = Pair<InMemoryKeyState, InMemoryKeyReply<A>>

/** Reducers run inside Shared retries; callers prepare key material, ids and clock readings beforehand. */
internal class InMemoryRootChanges(private val store: InMemoryKeyStore) {
    fun <A> observe(op: KeyOperation, root: RootKey, read: (InMemoryKeyState, InMemoryRoot) -> A) =
        change(op, root) { current, held -> current to read(current, held).right() }

    fun <A> change(op: KeyOperation, root: RootKey, reduce: (InMemoryKeyState, InMemoryRoot) -> InMemoryChange<A>) =
        call(op, root) { current ->
            current.roots[root]?.let { reduce(current, it) }
                ?: (current to failure(op, KeyFailureCode.NotObserved))
        }

    fun <A> call(
        op: KeyOperation,
        root: RootKey?,
        reduce: (InMemoryKeyState) -> InMemoryChange<A>,
    ): InMemoryKeyReply<A> =
        store.state.modify { current ->
            fun fault(after: Boolean) = current.faults.indexOfFirst {
                it.operation == op && it.afterSuccess == after && (it.root == null || it.root == root)
            }
            val before = fault(false)
            val (next, result) = if (before >= 0) current to failure(op, current.faults[before].code)
            else reduce(current)
            val index = if (before >= 0) before else if (result is Either.Right) fault(true) else -1
            if (index < 0) next to result else {
                val remaining = next.faults.filterIndexed { position, _ -> position != index }
                next.copy(faults = remaining) to failure(op, current.faults[index].code)
            }
        }

    fun <A> afterData(
        op: KeyOperation, root: RootKey, reply: InMemoryKeyReply<A>, erase: (A) -> Unit,
    ): InMemoryKeyReply<A> {
        if (reply is Either.Left) return reply
        var delivered = false
        try {
            val result = store.state.modify { current ->
                val index = current.faults.indexOfFirst {
                    it.operation == op && it.afterSuccess && (it.root == null || it.root == root)
                }
                if (index < 0) current to reply else current.copy(
                    faults = current.faults.filterIndexed { position, _ -> position != index },
                ) to failure(op, current.faults[index].code)
            }
            delivered = result is Either.Right
            return result
        } finally {
            if (!delivered && reply is Either.Right) erase(reply.value)
        }
    }

    fun preflight(expected: ExpectedKeySet): InMemoryKeyReply<Unit> = attempt {
        (listOf(expected.primary) + expected.replicas).forEach {
            observe(KeyOperation.Describe, it.location.root) { current, held -> current.metadata(held) }.bind()
            observe(KeyOperation.Tags, it.location.root) { _, held -> KeyTags(held.creation.tags) }.bind()
        }
        validate(store.state.peek(), expected)?.let { fail(it) }
    }

    fun validate(current: InMemoryKeyState, expected: ExpectedKeySet): KeyOperationFailure? {
        val members = listOf(expected.primary) + expected.replicas
        val roots = members.mapNotNull { current.roots[it.location.root] }
        val primary = expected.primary.location
        if (roots.size != members.size) return KeyOperationFailure(
            KeyOperation.ScheduleDeletion, KeyFailureCode.NotObserved,
        )
        val mismatch = members.map { it.location.root }.distinct().size != members.size ||
            roots.zip(members).any { (held, wanted) ->
                held.location != wanted.location || held.primary != primary.root ||
                    wanted.requiredTags.any { held.creation.tags[it.key] != it.value }
            } || current.metadata(roots.first()).replicas.toSet() != expected.replicas.map { it.location }.toSet()
        return when {
            mismatch -> KeyOperationFailure(KeyOperation.ScheduleDeletion, KeyFailureCode.Conflict)

            roots.any { it.state in retryableStates } ->
                KeyOperationFailure(KeyOperation.ScheduleDeletion, KeyFailureCode.Unavailable)

            else -> null
        }
    }

    private fun failure(op: KeyOperation, code: KeyFailureCode) = KeyOperationFailure(op, code).left()
}

internal val retryableStates = listOf(RootState.Creating, RootState.Updating, RootState.Unavailable, RootState.Unknown)
