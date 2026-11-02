package io.github.matthewjones372.surefold.tenancy.crypto

import dev.surefold.Shared
import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right
import java.time.Instant

internal data class InMemoryRoot(
    val location: RootLocation,
    val primary: RootKey,
    val creation: RootCreation,
    val key: InMemoryWrappingKey,
    val state: RootState = RootState.Enabled,
    val rotation: Boolean = false,
    val deletionAt: Instant? = null,
)

internal data class InMemoryFault(
    val operation: KeyOperation,
    val code: KeyFailureCode,
    val root: RootKey? = null,
    val afterSuccess: Boolean = false,
)

internal data class InMemoryKeyState(
    val roots: Map<RootKey, InMemoryRoot> = emptyMap(),
    val aliases: Map<Pair<String, String>, RootKey> = emptyMap(),
    val faults: List<InMemoryFault> = emptyList(),
)

internal class InMemoryKeyStore(initial: InMemoryKeyState = InMemoryKeyState()) {
    val state = Shared(
        initial.copy(
            roots = initial.roots.toMap(),
            aliases = initial.aliases.toMap(),
            faults = initial.faults.toList(),
        ),
    )

    fun lookup(root: RootKey, operation: KeyOperation): Either<KeyOperationFailure, InMemoryWrappingKey> =
        state.modify { snapshot ->
            val index = snapshot.faults.indexOfFirst {
                it.operation == operation && !it.afterSuccess && (it.root == null || it.root == root)
            }
            if (index >= 0) {
                snapshot.copy(faults = snapshot.faults.filterIndexed { position, _ -> position != index }) to
                    KeyOperationFailure(operation, snapshot.faults[index].code).left()
            } else {
                snapshot to held(snapshot.roots[root], operation)
            }
        }

    private fun held(root: InMemoryRoot?, operation: KeyOperation): Either<KeyOperationFailure, InMemoryWrappingKey> =
        when (root?.state) {
            null -> KeyOperationFailure(operation, KeyFailureCode.NotObserved).left()

            RootState.Enabled -> root.key.right()

            RootState.Creating, RootState.Updating, RootState.Unavailable, RootState.Unknown ->
                KeyOperationFailure(operation, KeyFailureCode.Unavailable).left()

            RootState.Disabled, RootState.PendingDeletion, RootState.PendingReplicaDeletion ->
                KeyOperationFailure(operation, KeyFailureCode.Refused).left()
        }
}

internal fun InMemoryKeyState.metadata(root: InMemoryRoot): RootMetadata = RootMetadata(
    root.location,
    root.state,
    roots[root.primary]?.location,
    roots.values.filter { it.primary == root.primary && it.location.root != root.primary }.map { it.location },
)
