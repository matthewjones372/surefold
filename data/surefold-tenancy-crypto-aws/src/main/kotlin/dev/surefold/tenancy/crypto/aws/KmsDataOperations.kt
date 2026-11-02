package dev.surefold.tenancy.crypto.aws

import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.ensure
import dev.surefold.fail.ensureNotNull
import dev.surefold.fail.right
import dev.surefold.tenancy.crypto.DataKeyOperations
import dev.surefold.tenancy.crypto.EncryptionContext
import dev.surefold.tenancy.crypto.GeneratedKey
import dev.surefold.tenancy.crypto.KeyFailureCode
import dev.surefold.tenancy.crypto.KeyOperation
import dev.surefold.tenancy.crypto.KeyOperationFailure
import dev.surefold.tenancy.crypto.RootKey
import dev.surefold.tenancy.crypto.RootState
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kms.model.DataKeySpec
import software.amazon.awssdk.services.kms.model.DecryptRequest
import software.amazon.awssdk.services.kms.model.EncryptRequest
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest
import software.amazon.awssdk.services.kms.model.KmsInvalidStateException

internal class KmsDataOperations(
    private val observations: KmsObservations,
    private val preferredDataRegion: String? = null,
) : DataKeyOperations {
    override fun generateDataKey(root: RootKey, context: EncryptionContext): Either<KeyOperationFailure, GeneratedKey> =
        kmsCall(KeyOperation.Generate) {
            attempt {
                val op = KeyOperation.Generate
                val identity = dataIdentity(root, op).bind()
                val client = observations.client(identity.location.region, op).bind()
                val response = dataCall(identity, op) {
                    client.generateDataKey(
                        GenerateDataKeyRequest.builder().keyId(identity.location.root.id).keySpec(DataKeySpec.AES_256)
                            .encryptionContext(context.entries).build(),
                    )
                }.bind()
                transferPlaintext(response.plaintext(), op) { plaintext ->
                    verifyDataIdentity(identity, response.keyId(), op).bind()
                    ensure(plaintext.size == KEY_BYTES) { incomplete(op) }
                    GeneratedKey(plaintext, ciphertext(response.ciphertextBlob(), op).bind())
                }.bind()
            }
        }

    override fun wrap(
        root: RootKey,
        plaintext: ByteArray,
        context: EncryptionContext,
    ): Either<KeyOperationFailure, ByteArray> = kmsCall(KeyOperation.Wrap) {
        attempt {
            val op = KeyOperation.Wrap
            val identity = dataIdentity(root, op).bind()
            val client = observations.client(identity.location.region, op).bind()
            val input = SdkBytes.fromByteArray(plaintext)
            try {
                val response = dataCall(identity, op) {
                    client.encrypt(
                        EncryptRequest.builder().keyId(identity.location.root.id).plaintext(input)
                            .encryptionContext(context.entries).build(),
                    )
                }.bind()
                verifyDataIdentity(identity, response.keyId(), op).bind()
                ciphertext(response.ciphertextBlob(), op).bind()
            } finally {
                input.asByteArrayUnsafe().fill(0)
            }
        }
    }

    override fun unwrap(
        root: RootKey,
        wrapped: ByteArray,
        context: EncryptionContext,
    ): Either<KeyOperationFailure, ByteArray> = kmsCall(KeyOperation.Unwrap) {
        attempt {
            val op = KeyOperation.Unwrap
            val identity = dataIdentity(root, op).bind()
            val client = observations.client(identity.location.region, op).bind()
            val response = dataCall(identity, op) {
                client.decrypt(
                    DecryptRequest.builder().keyId(identity.location.root.id)
                        .ciphertextBlob(SdkBytes.fromByteArray(wrapped))
                        .encryptionContext(context.entries).build(),
                )
            }.bind()
            transferPlaintext(response.plaintext(), op) { plaintext ->
                verifyDataIdentity(identity, response.keyId(), op).bind()
                plaintext
            }.bind()
        }
    }

    private fun <A> dataCall(
        identity: KmsIdentity,
        operation: KeyOperation,
        call: () -> A,
    ): Either<KeyOperationFailure, A> {
        var invalidState = false
        val result = kmsCall(operation) {
            try {
                call().right()
            } catch (refused: KmsInvalidStateException) {
                invalidState = true
                throw refused
            }
        }
        return if (!invalidState) result else attempt {
            val state = observations.describe(identity.location.root).bind().metadata.state
            fail(KeyOperationFailure(operation, stateFailure(state)))
        }
    }

    private fun dataIdentity(root: RootKey, op: KeyOperation): Either<KeyOperationFailure, KmsIdentity> = attempt {
        val source = KmsIdentity.parse(root, op).bind()
        val region = preferredDataRegion
        if (region == null || region == source.location.region) return@attempt source
        ensure(source.keyId.startsWith("mrk-")) { conflict(op) }
        val location = source.location
        val candidate = KmsIdentity.parse(
            RootKey("arn:${location.partition}:kms:$region:${location.account}:key/${source.keyId}"), op,
        ).bind()
        val observed = observations.describe(candidate.location.root).bind().metadata
        val related = listOfNotNull(observed.primary) + observed.replicas
        ensure(source.location in related && candidate.location in related) { conflict(op) }
        ensure(observed.state == RootState.Enabled) { KeyOperationFailure(op, stateFailure(observed.state)) }
        candidate
    }

    companion object {
        private const val KEY_BYTES = 32
    }
}

private fun verifyDataIdentity(identity: KmsIdentity, returnedArn: String?, operation: KeyOperation) =
    identity.verify(returnedArn, identity.keyId, identity.location.account, operation)

private fun ciphertext(raw: SdkBytes?, operation: KeyOperation): Either<KeyOperationFailure, ByteArray> = attempt {
    val bytes = ensureNotNull(raw) { incomplete(operation) }.asByteArray()
    ensure(bytes.isNotEmpty()) { incomplete(operation) }
    bytes
}

/** Erases accessible SDK buffers; SDK marshalling and transport copies cannot be erased by this adapter. */
private fun <A> transferPlaintext(
    raw: SdkBytes?,
    operation: KeyOperation,
    transfer: Fail<KeyOperationFailure>.(ByteArray) -> A,
): Either<KeyOperationFailure, A> {
    val plaintext = raw?.asByteArray()
    var transferred = false
    try {
        return attempt {
            val bytes = ensureNotNull(plaintext) { incomplete(operation) }
            ensure(bytes.isNotEmpty()) { incomplete(operation) }
            transfer(bytes).also { transferred = true }
        }
    } finally {
        raw?.asByteArrayUnsafe()?.fill(0)
        if (!transferred) plaintext?.fill(0)
    }
}

private fun stateFailure(state: RootState): KeyFailureCode = when (state) {
    RootState.Creating, RootState.Updating, RootState.Unavailable, RootState.Unknown -> KeyFailureCode.Unavailable

    RootState.Enabled, RootState.Disabled, RootState.PendingDeletion, RootState.PendingReplicaDeletion ->
        KeyFailureCode.Refused
}
