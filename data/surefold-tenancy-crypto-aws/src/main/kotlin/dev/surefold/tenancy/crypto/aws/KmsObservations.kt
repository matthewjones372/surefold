package dev.surefold.tenancy.crypto.aws

import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.ensure
import dev.surefold.fail.ensureNotNull
import dev.surefold.tenancy.crypto.KeyFailureCode
import dev.surefold.tenancy.crypto.KeyOperation
import dev.surefold.tenancy.crypto.KeyOperationFailure
import dev.surefold.tenancy.crypto.KeyTags
import dev.surefold.tenancy.crypto.RootKey
import dev.surefold.tenancy.crypto.RootListing
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.model.DescribeKeyRequest
import software.amazon.awssdk.services.kms.model.ListKeysRequest
import software.amazon.awssdk.services.kms.model.ListResourceTagsRequest

internal class KmsObservations(clients: Map<String, KmsClient>) {
    private val regional = clients.toMap()

    fun client(region: String, operation: KeyOperation): Either<KeyOperationFailure, KmsClient> = attempt {
        val client = ensureNotNull(regional[region]) { KeyOperationFailure(operation, KeyFailureCode.Unavailable) }
        ensure(client.serviceClientConfiguration().region()?.id() == region) {
            KeyOperationFailure(operation, KeyFailureCode.Conflict)
        }
        client
    }

    fun describe(root: RootKey): Either<KeyOperationFailure, KmsObservation> = kmsCall(KeyOperation.Describe) {
        attempt {
            val identity = KmsIdentity.parse(root, KeyOperation.Describe).bind()
            val sdk = client(identity.location.region, KeyOperation.Describe).bind()
            val raw = sdk.describeKey(DescribeKeyRequest.builder().keyId(root.id).build()).keyMetadata()
            observeKmsMetadata(raw, identity.location.region, identity).bind()
        }
    }

    fun resolveAlias(region: String, alias: String): Either<KeyOperationFailure, KmsObservation> =
        kmsCall(KeyOperation.ResolveAlias) {
            attempt {
                // DescribeKey can create an AWS managed key when given a predefined AWS alias.
                ensure(
                    alias.length <= MAX_ALIAS_LENGTH && aliasPattern.matches(alias) && !alias.startsWith("alias/aws/"),
                ) {
                    KeyOperationFailure(KeyOperation.ResolveAlias, KeyFailureCode.InvalidInput)
                }
                val sdk = client(region, KeyOperation.ResolveAlias).bind()
                val raw = sdk.describeKey(DescribeKeyRequest.builder().keyId(alias).build()).keyMetadata()
                observeKmsMetadata(raw, region).bind()
            }
        }

    fun listRoots(region: String): Either<KeyOperationFailure, RootListing> = kmsCall(KeyOperation.ListRoots) {
        attempt {
            val sdk = client(region, KeyOperation.ListRoots).bind()
            val entries = kmsPages(KeyOperation.ListRoots) { marker ->
                val page = sdk.listKeys(ListKeysRequest.builder().marker(marker).build())
                KmsPage(page.keys(), page.hasKeys(), page.truncated(), page.nextMarker())
            }.bind()
            val identities = entries.map { entry ->
                val arn = ensureNotNull(entry.keyArn()) { incomplete(KeyOperation.ListRoots) }
                val identity = KmsIdentity.parse(RootKey(arn), KeyOperation.ListRoots).getOrNull()
                val verified = ensureNotNull(identity) { incomplete(KeyOperation.ListRoots) }
                verified.verify(arn, entry.keyId(), verified.location.account, KeyOperation.ListRoots).bind()
                ensure(verified.location.region == region) { conflict(KeyOperation.ListRoots) }
                verified.location
            }
            ensure(identities.distinct().size == identities.size) { conflict(KeyOperation.ListRoots) }
            RootListing(identities)
        }
    }

    fun tags(root: RootKey): Either<KeyOperationFailure, KeyTags> = kmsCall(KeyOperation.Tags) {
        attempt {
            val identity = KmsIdentity.parse(root, KeyOperation.Tags).bind()
            val sdk = client(identity.location.region, KeyOperation.Tags).bind()
            val entries = kmsPages(KeyOperation.Tags) { marker ->
                val page = sdk.listResourceTags(ListResourceTagsRequest.builder().keyId(root.id).marker(marker).build())
                KmsPage(page.tags(), page.hasTags(), page.truncated(), page.nextMarker())
            }.bind().map { tag ->
                val key = ensureNotNull(tag.tagKey()) { incomplete(KeyOperation.Tags) }
                ensure(key.isNotEmpty()) { incomplete(KeyOperation.Tags) }
                key to ensureNotNull(tag.tagValue()) { incomplete(KeyOperation.Tags) }
            }
            ensure(entries.map { it.first }.distinct().size == entries.size) { conflict(KeyOperation.Tags) }
            KeyTags(entries.toMap())
        }
    }

    companion object {
        private const val MAX_ALIAS_LENGTH = 256
        private val aliasPattern = Regex("alias/[a-zA-Z0-9/_-]+")
    }
}

internal fun incomplete(operation: KeyOperation) = KeyOperationFailure(operation, KeyFailureCode.IncompleteEvidence)
internal fun conflict(operation: KeyOperation) = KeyOperationFailure(operation, KeyFailureCode.Conflict)
