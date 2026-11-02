package io.github.matthewjones372.surefold.tenancy.crypto.aws

import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.ensure
import dev.surefold.fail.ensureNotNull
import dev.surefold.fail.right
import io.github.matthewjones372.surefold.tenancy.crypto.KeyFailureCode
import io.github.matthewjones372.surefold.tenancy.crypto.KeyOperation
import io.github.matthewjones372.surefold.tenancy.crypto.KeyOperationFailure
import io.github.matthewjones372.surefold.tenancy.crypto.RootCreation
import io.github.matthewjones372.surefold.tenancy.crypto.RootKey
import io.github.matthewjones372.surefold.tenancy.crypto.RootMetadata
import io.github.matthewjones372.surefold.tenancy.crypto.RootState
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.model.AlreadyExistsException
import software.amazon.awssdk.services.kms.model.CreateAliasRequest
import software.amazon.awssdk.services.kms.model.CreateKeyRequest
import software.amazon.awssdk.services.kms.model.EnableKeyRotationRequest
import software.amazon.awssdk.services.kms.model.KeySpec
import software.amazon.awssdk.services.kms.model.KeyUsageType
import software.amazon.awssdk.services.kms.model.Tag

internal class KmsRootCreation(
    private val observations: KmsObservations,
    creationClients: Map<String, KmsClient>,
) {
    private val creation = KmsObservations(creationClients)

    fun createPrimary(request: RootCreation): Either<KeyOperationFailure, RootMetadata> =
        kmsCall(KeyOperation.CreatePrimary, mutation = true) {
            attempt {
                val op = KeyOperation.CreatePrimary
                ensure(request.region.isNotBlank() && request.policy.isNotBlank()) {
                    KeyOperationFailure(op, KeyFailureCode.InvalidInput)
                }
                val sdk = singleAttemptClient(creation, request.region, op).bind()
                val response = sdk.createKey(
                    CreateKeyRequest.builder().policy(request.policy).description(request.description)
                        .tags(request.tags.map { (key, value) -> Tag.builder().tagKey(key).tagValue(value).build() })
                        .keySpec(KeySpec.SYMMETRIC_DEFAULT).keyUsage(KeyUsageType.ENCRYPT_DECRYPT)
                        .multiRegion(true).build(),
                )
                val uncertain = KeyOperationFailure(op, KeyFailureCode.UncertainMutation)
                val observed = ensureNotNull(observeKmsMetadata(response.keyMetadata(), request.region).getOrNull()) {
                    uncertain
                }
                ensure(
                    observed.metadata.primary == observed.metadata.location &&
                        observed.metadata.state == RootState.Enabled &&
                        observed.provider.keySpec() == KeySpec.SYMMETRIC_DEFAULT &&
                        observed.provider.keyUsage() == KeyUsageType.ENCRYPT_DECRYPT,
                ) { uncertain }
                observed.metadata
            }
        }

    fun bindAlias(root: RootKey, alias: String): Either<KeyOperationFailure, Unit> = kmsCall(KeyOperation.BindAlias) {
        attempt {
            val op = KeyOperation.BindAlias
            val identity = KmsIdentity.parse(root, op).bind()
            val region = identity.location.region
            val existing = observations.resolveAlias(region, alias)
            when (existing) {
                is Either.Right -> ensure(existing.value.metadata.location == identity.location) { conflict(op) }

                is Either.Left -> {
                    ensure(existing.value.code == KeyFailureCode.NotObserved) { existing.value }
                    bindMissingAlias(identity, alias).bind()
                }
            }
        }
    }

    private fun bindMissingAlias(identity: KmsIdentity, alias: String): Either<KeyOperationFailure, Unit> =
        kmsCall(KeyOperation.BindAlias, mutation = true) {
            attempt {
                val op = KeyOperation.BindAlias
                val region = identity.location.region
                val sdk = observations.client(region, op).bind()
                try {
                    sdk.createAlias(
                        CreateAliasRequest.builder().aliasName(alias)
                            .targetKeyId(identity.location.root.id).build(),
                    )
                } catch (alreadyExists: AlreadyExistsException) {
                    propagateCancellation(alreadyExists)
                    val raced = observations.resolveAlias(region, alias).bind()
                    ensure(raced.metadata.location == identity.location) { conflict(op) }
                }
            }
        }

    fun enableRotation(root: RootKey): Either<KeyOperationFailure, Unit> = kmsCall(KeyOperation.EnableRotation) {
        attempt {
            val op = KeyOperation.EnableRotation
            val observed = observations.describe(root).bind()
            ensure(observed.metadata.primary == observed.metadata.location) {
                KeyOperationFailure(op, KeyFailureCode.Refused)
            }
            when (observed.metadata.state) {
                RootState.Enabled -> Unit

                RootState.Creating, RootState.Updating, RootState.Unavailable, RootState.Unknown ->
                    fail(KeyOperationFailure(op, KeyFailureCode.Unavailable))

                RootState.Disabled, RootState.PendingDeletion, RootState.PendingReplicaDeletion ->
                    fail(KeyOperationFailure(op, KeyFailureCode.Refused))
            }
            val sdk = observations.client(observed.metadata.location.region, op).bind()
            kmsCall(op, mutation = true) {
                sdk.enableKeyRotation(EnableKeyRotationRequest.builder().keyId(root.id).build())
                Unit.right()
            }.bind()
        }
    }
}
