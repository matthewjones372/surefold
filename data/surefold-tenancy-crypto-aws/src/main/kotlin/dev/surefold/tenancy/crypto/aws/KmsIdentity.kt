package dev.surefold.tenancy.crypto.aws

import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.tenancy.crypto.KeyFailureCode
import dev.surefold.tenancy.crypto.KeyOperation
import dev.surefold.tenancy.crypto.KeyOperationFailure
import dev.surefold.tenancy.crypto.RootKey
import dev.surefold.tenancy.crypto.RootLocation
import software.amazon.awssdk.regions.PartitionMetadata

internal class KmsIdentity(val location: RootLocation, val keyId: String) {
    fun verify(
        arn: String?,
        keyId: String?,
        account: String?,
        operation: KeyOperation,
    ): Either<KeyOperationFailure, Unit> {
        val returned = arn?.let { parse(RootKey(it), operation).getOrNull() }
        return when {
            returned == null || keyId.isNullOrBlank() || account.isNullOrBlank() ->
                KeyOperationFailure(operation, KeyFailureCode.IncompleteEvidence).left()

            returned.location != location || keyId != this.keyId || account != location.account ->
                KeyOperationFailure(operation, KeyFailureCode.Conflict).left()

            else -> Unit.right()
        }
    }

    override fun toString(): String = "KmsIdentity(redacted)"

    companion object {
        fun parse(root: RootKey, operation: KeyOperation): Either<KeyOperationFailure, KmsIdentity> {
            val parts = keyArn.matchEntire(root.id)?.groupValues
                ?: return KeyOperationFailure(operation, KeyFailureCode.InvalidInput).left()
            val (_, partition, region, account, keyId) = parts
            val metadata = PartitionMetadata.of(partition)
            return if (metadata != null && Regex(metadata.regionRegex()).matches(region)) {
                KmsIdentity(RootLocation(root, account, partition, region), keyId).right()
            } else KeyOperationFailure(operation, KeyFailureCode.InvalidInput).left()
        }

        private const val UUID_KEY_ID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        private val keyArn = Regex(
            "arn:([^:]+):kms:([a-z]+(?:-[a-z]+)+-[0-9]+):([0-9]{12}):key/($UUID_KEY_ID|mrk-[0-9a-f]{32})",
        )
    }
}
