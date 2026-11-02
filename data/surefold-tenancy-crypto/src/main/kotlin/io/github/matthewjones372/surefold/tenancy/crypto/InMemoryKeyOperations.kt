package io.github.matthewjones372.surefold.tenancy.crypto

import dev.surefold.Clock
import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right
import java.time.Instant
import java.util.UUID

/** Local protocol fake: accepted deletion stays observable until a test explicitly changes its state. */
class InMemoryKeyOperations(
    private val clock: Clock,
    private val account: String = "memory",
    private val partition: String = "memory",
) : KeyOperations {
    private val store = InMemoryKeyStore()
    private val changes = InMemoryRootChanges(store)
    private val data = InMemoryDataKeyOperations(lookup = store::lookup)
    private val deletionWaitingDays = 7..30

    override fun generateDataKey(root: RootKey, context: EncryptionContext) =
        changes.afterData(KeyOperation.Generate, root, data.generateDataKey(root, context)) {
            it.plaintext.fill(0)
            it.wrapped.fill(0)
        }
    override fun wrap(root: RootKey, plaintext: ByteArray, context: EncryptionContext) =
        changes.afterData(KeyOperation.Wrap, root, data.wrap(root, plaintext, context)) { it.fill(0) }
    override fun unwrap(root: RootKey, wrapped: ByteArray, context: EncryptionContext) =
        changes.afterData(KeyOperation.Unwrap, root, data.unwrap(root, wrapped, context)) { it.fill(0) }

    fun failNext(op: KeyOperation, code: KeyFailureCode, root: RootKey? = null, afterSuccess: Boolean = false) {
        store.state.update { it.copy(faults = it.faults + InMemoryFault(op, code, root, afterSuccess)) }
    }

    fun setState(root: RootKey, state: RootState, effectiveAt: Instant? = null) {
        store.state.update { current ->
            val held = current.roots[root]
            if (held == null) current else
                current.copy(roots = current.roots + (root to held.copy(state = state, deletionAt = effectiveAt)))
        }
    }

    fun creation(root: RootKey): RootCreation? = store.state.peek().roots[root]?.creation
    fun rotationEnabled(root: RootKey): Boolean = store.state.peek().roots[root]?.rotation == true

    override fun describe(root: RootKey) = changes.observe(KeyOperation.Describe, root) { current, held ->
        current.metadata(held)
    }
    override fun tags(root: RootKey) = changes.observe(KeyOperation.Tags, root) { _, held ->
        KeyTags(held.creation.tags)
    }
    override fun listRoots(region: String) = changes.call(KeyOperation.ListRoots, null) { current ->
        val locations = current.roots.values.filter { it.location.region == region }.map { it.location }
        current to RootListing(locations).right()
    }

    override fun resolveAlias(region: String, alias: String): Either<KeyOperationFailure, RootMetadata> {
        val target = store.state.peek().aliases[region to alias]
        return changes.call(KeyOperation.ResolveAlias, target) { current ->
            val held = current.aliases[region to alias]?.let(current.roots::get)
            val observed = held?.let { current.metadata(it).right() }
                ?: failure(KeyOperation.ResolveAlias, KeyFailureCode.NotObserved)
            current to observed
        }
    }

    override fun createPrimary(request: RootCreation): Either<KeyOperationFailure, RootMetadata> {
        val root = RootKey("in-memory/${UUID.randomUUID()}")
        val location = RootLocation(root, account, partition, request.region)
        val held = InMemoryRoot(location, root, request, InMemoryWrappingKey())
        return changes.call(KeyOperation.CreatePrimary, null) { current ->
            val next = current.copy(roots = current.roots + (root to held))
            if (valid(request)) next to next.metadata(held).right()
            else current to failure(KeyOperation.CreatePrimary, KeyFailureCode.InvalidInput)
        }
    }

    override fun bindAlias(root: RootKey, alias: String) =
        changes.change(KeyOperation.BindAlias, root) { current, held ->
            val binding = held.location.region to alias
            when {
                alias.isBlank() -> current to failure(KeyOperation.BindAlias, KeyFailureCode.InvalidInput)

                current.aliases[binding]?.let { it != root } == true ->
                    current to failure(KeyOperation.BindAlias, KeyFailureCode.Conflict)

                else -> current.copy(aliases = current.aliases + (binding to root)) to Unit.right()
            }
        }

    override fun enableRotation(root: RootKey) = changes.change(KeyOperation.EnableRotation, root) { current, held ->
        val refused = if (held.primary == root) mutationFailure(held) else KeyFailureCode.Refused
        if (refused != null) current to failure(KeyOperation.EnableRotation, refused)
        else current.copy(
            roots = current.roots.mapValues { (_, regional) ->
                if (regional.primary == held.primary) regional.copy(rotation = true) else regional
            },
        ) to Unit.right()
    }

    override fun createReplica(primary: RootKey, request: RootCreation): Either<KeyOperationFailure, RootMetadata> {
        val root = RootKey("in-memory/${UUID.randomUUID()}")
        return changes.change(KeyOperation.CreateReplica, primary) { current, held ->
            val existing = current.roots.values.find { it.primary == primary && it.location.region == request.region }
            val location = held.location.copy(root = root, region = request.region)
            val replica = held.copy(location = location, creation = request)
            val next = current.copy(roots = current.roots + (root to replica))
            val refused = mutationFailure(held)
            when {
                !valid(request) -> current to failure(KeyOperation.CreateReplica, KeyFailureCode.InvalidInput)

                held.primary != primary || request.region == held.location.region ->
                    current to failure(KeyOperation.CreateReplica, KeyFailureCode.Conflict)

                refused != null -> current to failure(KeyOperation.CreateReplica, refused)

                existing != null && !sameCreation(existing.creation, request) ->
                    current to failure(KeyOperation.CreateReplica, KeyFailureCode.Conflict)

                existing != null -> current to current.metadata(existing).right()

                else -> next to next.metadata(replica).right()
            }
        }
    }

    override fun inspectReplica(primary: RootKey, replica: RootKey) =
        changes.change(KeyOperation.InspectReplica, replica) { current, held ->
            val related = held.primary == primary && replica != primary && current.roots[primary]?.primary == primary
            current to if (related) current.metadata(held).right()
            else failure(KeyOperation.InspectReplica, KeyFailureCode.Conflict)
        }

    override fun scheduleDeletion(expected: ExpectedKeySet, waitingDays: Int): DeletionProgress {
        if (waitingDays !in deletionWaitingDays) return DeletionProgress(
            emptyList(), KeyOperationFailure(KeyOperation.ScheduleDeletion, KeyFailureCode.InvalidInput),
        )
        val preflight = changes.preflight(expected)
        if (preflight is Either.Left) return DeletionProgress(emptyList(), preflight.value)
        val deadline = clock.now().plusSeconds(waitingDays * 86400L)
        return (expected.replicas + expected.primary).fold(DeletionProgress(emptyList(), null)) { progress, member ->
            if (progress.failure != null) progress else {
                val accepted = changes.call(KeyOperation.ScheduleDeletion, member.location.root) { current ->
                    val invalid = changes.validate(current, expected)
                    if (invalid != null) return@call current to invalid.left()
                    val held = current.roots.getValue(member.location.root)
                    val replicas = current.metadata(held).replicas
                    val pending = held.state in listOf(RootState.PendingDeletion, RootState.PendingReplicaDeletion)
                    val status = if (held.primary == held.location.root && replicas.isNotEmpty())
                        RootState.PendingReplicaDeletion else RootState.PendingDeletion
                    val date = if (status == RootState.PendingDeletion) deadline else null
                    val scheduled = if (pending) held else held.copy(state = status, deletionAt = date)
                    val evidence = if (scheduled.state == RootState.PendingDeletion)
                        DeletionState.PendingDeletion else DeletionState.PendingReplicaDeletion
                    current.copy(roots = current.roots + (held.location.root to scheduled)) to
                        RegionalDeletion(scheduled.location, evidence, scheduled.deletionAt).right()
                }
                when (accepted) {
                    is Either.Left -> DeletionProgress(progress.regions, accepted.value)
                    is Either.Right -> DeletionProgress(progress.regions + accepted.value, null)
                }
            }
        }
    }

    private fun mutationFailure(root: InMemoryRoot) = if (root.state == RootState.Enabled) null
    else if (root.state in retryableStates) KeyFailureCode.Unavailable else KeyFailureCode.Refused
    private fun valid(request: RootCreation) = request.region.isNotBlank() && request.policy.isNotBlank()
    private fun sameCreation(a: RootCreation, b: RootCreation) =
        a.policy == b.policy && a.description == b.description && a.tags == b.tags
    private fun failure(op: KeyOperation, code: KeyFailureCode) = KeyOperationFailure(op, code).left()
}
