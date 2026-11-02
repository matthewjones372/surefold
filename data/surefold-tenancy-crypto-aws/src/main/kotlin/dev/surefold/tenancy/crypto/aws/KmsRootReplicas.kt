package dev.surefold.tenancy.crypto.aws

import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.ensure
import dev.surefold.fail.ensureNotNull
import dev.surefold.tenancy.crypto.KeyFailureCode
import dev.surefold.tenancy.crypto.KeyOperation
import dev.surefold.tenancy.crypto.KeyOperationFailure
import dev.surefold.tenancy.crypto.RootCreation
import dev.surefold.tenancy.crypto.RootKey
import dev.surefold.tenancy.crypto.RootMetadata
import dev.surefold.tenancy.crypto.RootState
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.model.AlreadyExistsException
import software.amazon.awssdk.services.kms.model.ReplicateKeyRequest
import software.amazon.awssdk.services.kms.model.Tag

internal class KmsRootReplicas(
    private val observations: KmsObservations,
    creationClients: Map<String, KmsClient>,
) {
    private val creation = KmsObservations(creationClients)

    /** Creating records an accepted stage; inspectReplica must confirm Enabled before use. */
    fun createReplica(primary: RootKey, request: RootCreation): Either<KeyOperationFailure, RootMetadata> =
        kmsCall(KeyOperation.CreateReplica) {
            attempt {
                val op = KeyOperation.CreateReplica
                val identity = KmsIdentity.parse(primary, op).bind()
                val location = identity.location
                ensure(request.policy.isNotBlank() && request.region != location.region) {
                    KeyOperationFailure(op, KeyFailureCode.InvalidInput)
                }
                val arn = "arn:${location.partition}:kms:${request.region}:${location.account}:key/${identity.keyId}"
                val target = KmsIdentity.parse(RootKey(arn), op).bind()
                val source = primaryMetadata(primary, op).bind()
                when (val local = observations.describe(target.location.root)) {
                    is Either.Right -> existing(source, local.value.metadata, request, op).bind()

                    is Either.Left -> {
                        ensure(local.value.code == KeyFailureCode.NotObserved) { local.value }
                        ensure(target.location !in source.replicas) { local.value }
                        replicate(source, target, request).bind()
                    }
                }
            }
        }

    fun inspectReplica(primary: RootKey, replica: RootKey): Either<KeyOperationFailure, RootMetadata> =
        kmsCall(KeyOperation.InspectReplica) {
            attempt {
                val op = KeyOperation.InspectReplica
                val sourceIdentity = KmsIdentity.parse(primary, op).bind()
                val target = KmsIdentity.parse(replica, op).bind()
                ensure(
                    sourceIdentity.keyId == target.keyId &&
                        sourceIdentity.location.account == target.location.account &&
                        sourceIdentity.location.partition == target.location.partition &&
                        sourceIdentity.location.region != target.location.region,
                ) { conflict(op) }
                val source = primaryMetadata(primary, op).bind()
                val local = observations.describe(replica).bind().metadata
                relationship(source, local, op).bind()
                ensure(local.state == RootState.Enabled) { KeyOperationFailure(op, replicaStateFailure(local.state)) }
                local
            }
        }

    private fun primaryMetadata(root: RootKey, op: KeyOperation): Either<KeyOperationFailure, RootMetadata> = attempt {
        val observed = observations.describe(root).bind().metadata
        ensure(observed.primary == observed.location) { KeyOperationFailure(op, KeyFailureCode.Refused) }
        ensure(observed.state == RootState.Enabled) { KeyOperationFailure(op, replicaStateFailure(observed.state)) }
        observed
    }

    private fun existing(
        source: RootMetadata,
        local: RootMetadata,
        request: RootCreation,
        op: KeyOperation,
    ): Either<KeyOperationFailure, RootMetadata> = attempt {
        relationship(source, local, op).bind()
        val tags = observations.tags(local.location.root).bind().entries
        ensure(request.tags.all { (key, value) -> tags[key] == value }) { conflict(op) }
        ensure(local.state == RootState.Enabled || local.state == RootState.Creating) {
            KeyOperationFailure(op, replicaStateFailure(local.state))
        }
        local
    }

    private fun replicate(
        source: RootMetadata,
        target: KmsIdentity,
        request: RootCreation,
    ): Either<KeyOperationFailure, RootMetadata> = kmsCall(KeyOperation.CreateReplica, mutation = true) {
        attempt {
            val op = KeyOperation.CreateReplica
            val sdk = singleAttemptClient(creation, source.location.region, op).bind()
            try {
                val response = sdk.replicateKey(
                    ReplicateKeyRequest.builder().keyId(source.location.root.id).replicaRegion(request.region)
                        .policy(request.policy).description(request.description)
                        .tags(request.tags.map { (key, value) -> Tag.builder().tagKey(key).tagValue(value).build() })
                        .build(),
                )
                val uncertain = KeyOperationFailure(op, KeyFailureCode.UncertainMutation)
                val local = ensureNotNull(
                    observeKmsMetadata(response.replicaKeyMetadata(), request.region, target).getOrNull(),
                ) { uncertain }.metadata
                ensure(
                    local.primary == source.location && local.location != source.location &&
                        local.replicas.toSet() == (source.replicas + target.location).toSet() &&
                        (local.state == RootState.Enabled || local.state == RootState.Creating),
                ) { uncertain }
                local
            } catch (alreadyExists: AlreadyExistsException) {
                propagateCancellation(alreadyExists)
                val refreshed = primaryMetadata(source.location.root, op).bind()
                val local = observations.describe(target.location.root).bind().metadata
                existing(refreshed, local, request, op).bind()
            }
        }
    }
}

private fun relationship(
    source: RootMetadata,
    local: RootMetadata,
    op: KeyOperation,
): Either<KeyOperationFailure, Unit> = attempt {
    ensure(
        local.primary == source.location && local.location != source.location &&
            local.location in source.replicas && local.replicas.toSet() == source.replicas.toSet(),
    ) { conflict(op) }
}

private fun replicaStateFailure(state: RootState): KeyFailureCode = when (state) {
    RootState.Creating, RootState.Updating, RootState.Unavailable, RootState.Unknown -> KeyFailureCode.Unavailable

    RootState.Enabled, RootState.Disabled, RootState.PendingDeletion, RootState.PendingReplicaDeletion ->
        KeyFailureCode.Refused
}
