package dev.surefold.tenancy.crypto.aws

import dev.surefold.Shared
import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.tenancy.crypto.EncryptionContext
import dev.surefold.tenancy.crypto.ExpectedKeySet
import dev.surefold.tenancy.crypto.KeyFailureCode
import dev.surefold.tenancy.crypto.KeyOperationFailure
import dev.surefold.tenancy.crypto.KeyOperations
import dev.surefold.tenancy.crypto.RootCreation
import dev.surefold.tenancy.crypto.RootExpectation
import dev.surefold.tenancy.crypto.RootKey
import dev.surefold.tenancy.crypto.RootLocation
import dev.surefold.tenancy.crypto.RootState
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.retries.StandardRetryStrategy
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.KmsServiceClientConfiguration
import software.amazon.awssdk.services.kms.model.CreateAliasRequest
import software.amazon.awssdk.services.kms.model.CreateAliasResponse
import software.amazon.awssdk.services.kms.model.CreateKeyRequest
import software.amazon.awssdk.services.kms.model.CreateKeyResponse
import software.amazon.awssdk.services.kms.model.DecryptRequest
import software.amazon.awssdk.services.kms.model.DecryptResponse
import software.amazon.awssdk.services.kms.model.DescribeKeyRequest
import software.amazon.awssdk.services.kms.model.DescribeKeyResponse
import software.amazon.awssdk.services.kms.model.EnableKeyRotationRequest
import software.amazon.awssdk.services.kms.model.EnableKeyRotationResponse
import software.amazon.awssdk.services.kms.model.EncryptRequest
import software.amazon.awssdk.services.kms.model.EncryptResponse
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse
import software.amazon.awssdk.services.kms.model.KeyListEntry
import software.amazon.awssdk.services.kms.model.KeyMetadata
import software.amazon.awssdk.services.kms.model.ListKeysRequest
import software.amazon.awssdk.services.kms.model.ListKeysResponse
import software.amazon.awssdk.services.kms.model.ListResourceTagsRequest
import software.amazon.awssdk.services.kms.model.ListResourceTagsResponse
import software.amazon.awssdk.services.kms.model.MultiRegionConfiguration
import software.amazon.awssdk.services.kms.model.MultiRegionKey
import software.amazon.awssdk.services.kms.model.NotFoundException
import software.amazon.awssdk.services.kms.model.ReplicateKeyRequest
import software.amazon.awssdk.services.kms.model.ReplicateKeyResponse
import software.amazon.awssdk.services.kms.model.ScheduleKeyDeletionRequest
import software.amazon.awssdk.services.kms.model.ScheduleKeyDeletionResponse
import software.amazon.awssdk.services.kms.model.Tag
import java.time.Instant

object AwsKeyOperationsSuite : Suite() {
    init {
        test("a selected data Region supports all data operations without a primary client") {
            val replica = PublicClient(SECONDARY)
            val operations = publicOperations(mapOf(SECONDARY to replica), preferred = SECONDARY)
            val root = RootKey(publicArn(PRIMARY))
            val context = EncryptionContext(mapOf("tenant_id" to "platform", "purpose" to "enc", "version" to "1"))
            operations.generateDataKey(root, context).value().plaintext.toList() shouldBe publicBytes().toList()
            operations.wrap(root, publicBytes(), context).value().toList() shouldBe listOf(9.toByte())
            operations.unwrap(root, byteArrayOf(9), context).value().toList() shouldBe publicBytes().toList()
            root.id shouldBe publicArn(PRIMARY)
            replica.contexts.peek() shouldBe List(3) { context.entries }
            replica.dataRoots.peek() shouldBe List(3) { publicArn(SECONDARY) }
            operations.resolveAlias(SECONDARY, "alias/proof").value().location.root shouldBe
                RootKey(publicArn(SECONDARY))
            operations.describe(root).failure().code shouldBe KeyFailureCode.Unavailable
            operations.resolveAlias(PRIMARY, "alias/proof").failure().code shouldBe KeyFailureCode.Unavailable
            operations.bindAlias(root, "alias/proof").failure().code shouldBe KeyFailureCode.Unavailable
            operations.enableRotation(root).failure().code shouldBe KeyFailureCode.Unavailable
            val expected = ExpectedKeySet(RootExpectation(publicMetadata(PRIMARY).location(), emptyMap()), emptyList())
            operations.scheduleDeletion(expected, 7).failure!!.code shouldBe KeyFailureCode.Unavailable
            replica.closed.peek() shouldBe false
        }

        test("the public port composes discovery explicit creation and replica first deletion") {
            val topology = Shared(false)
            val primary = PublicClient(PRIMARY, topology)
            val replica = PublicClient(SECONDARY, topology)
            val creator = PublicClient(PRIMARY, topology)
            val normal = linkedMapOf<String, KmsClient>(PRIMARY to primary, SECONDARY to replica)
            val creation = linkedMapOf<String, KmsClient>(PRIMARY to creator)
            val operations = publicOperations(normal, creation)
            normal.clear()
            creation.clear()
            val root = RootKey(publicArn(PRIMARY))
            val created = operations.createPrimary(RootCreation(PRIMARY, "policy", "proof", proofTags)).value()
            created.location.root shouldBe root
            created.state shouldBe RootState.Enabled
            creator.mutations.peek() shouldBe listOf("create")
            primary.mutations.peek() shouldBe emptyList()
            operations.bindAlias(root, "alias/proof").value()
            operations.enableRotation(root).value()
            operations.resolveAlias(PRIMARY, "alias/proof").value().location shouldBe created.location
            operations.describe(root).value().location shouldBe created.location
            operations.listRoots(PRIMARY).value().locations shouldBe listOf(created.location)
            operations.tags(root).value().entries shouldBe proofTags
            operations.createReplica(root, RootCreation(SECONDARY, "policy", "proof", proofTags)).value().state shouldBe
                RootState.Creating
            operations.inspectReplica(root, RootKey(publicArn(SECONDARY))).value().state shouldBe RootState.Enabled
            val expected = ExpectedKeySet(
                RootExpectation(created.location, proofTags),
                listOf(RootExpectation(operations.describe(RootKey(publicArn(SECONDARY))).value().location, proofTags)),
            )
            val deleted = operations.scheduleDeletion(expected, 7)
            deleted.failure shouldBe null
            deleted.regions.map { it.location.region } shouldBe listOf(SECONDARY, PRIMARY)
            deleted.regions.map { it.effectiveAt } shouldBe List(2) { proofDate }
            primary.mutations.peek() shouldBe listOf("alias", "rotation", "delete")
            replica.mutations.peek() shouldBe listOf("delete")
            creator.mutations.peek() shouldBe listOf("create", "replicate")
            listOf(primary, replica, creator).map { it.closed.peek() } shouldBe List(3) { false }
        }

        test("the default data Region keeps the supplied root and needs no topology read") {
            val client = PublicClient(PRIMARY)
            publicOperations(mapOf(PRIMARY to client)).generateDataKey(
                RootKey(publicArn(PRIMARY)), EncryptionContext(emptyMap()),
            ).value().plaintext.toList() shouldBe publicBytes().toList()
            client.dataRoots.peek() shouldBe listOf(publicArn(PRIMARY))
            client.described.peek() shouldBe emptyList()
        }
    }
}

private const val PRIMARY = "eu-west-2"
private const val SECONDARY = "eu-west-1"
private const val PUBLIC_ACCOUNT = "123456789012"
private val publicId = "mrk-${"b".repeat(32)}"
private val proofTags = mapOf("surefold:proof" to "disposable")
private val proofDate = Instant.parse("2030-01-01T00:00:00Z")
private fun publicArn(region: String) = "arn:aws:kms:$region:$PUBLIC_ACCOUNT:key/$publicId"
private fun publicBytes() = ByteArray(32) { 4 }
private fun publicRelation(region: String) = MultiRegionKey.builder().arn(publicArn(region)).region(region).build()
private fun publicMetadata(region: String, replicas: Boolean = true, state: String = "Enabled"): KeyMetadata =
    KeyMetadata.builder().arn(publicArn(region)).keyId(publicId).awsAccountId(PUBLIC_ACCOUNT).keyState(state)
        .keySpec("SYMMETRIC_DEFAULT").keyUsage("ENCRYPT_DECRYPT").multiRegion(true).multiRegionConfiguration(
            MultiRegionConfiguration.builder().multiRegionKeyType(if (region == PRIMARY) "PRIMARY" else "REPLICA")
                .primaryKey(publicRelation(PRIMARY))
                .replicaKeys(if (replicas) listOf(publicRelation(SECONDARY)) else emptyList()).build(),
        ).build()
private fun KeyMetadata.location() =
    RootLocation(RootKey(arn()), PUBLIC_ACCOUNT, "aws", PRIMARY)
private fun <A> Either<*, A>.value(): A = (this as Either.Right).value
private fun Either<KeyOperationFailure, *>.failure() =
    (this as Either.Left).value

private fun publicOperations(
    clients: Map<String, KmsClient>,
    creation: Map<String, KmsClient> = emptyMap(),
    preferred: String? = null,
): KeyOperations = AwsKeyOperations(clients, creation, preferred)

private class PublicClient(
    private val region: String,
    private val replicated: Shared<Boolean> = Shared(true),
) : KmsClient {
    val closed = Shared(false)
    val contexts = Shared(emptyList<Map<String, String>>())
    val dataRoots = Shared(emptyList<String>())
    val described = Shared(emptyList<String>())
    val mutations = Shared(emptyList<String>())
    private val alias = Shared(region == SECONDARY)
    override fun serviceName() = "kms"
    override fun close() { closed.update { true } }
    override fun serviceClientConfiguration(): KmsServiceClientConfiguration =
        KmsServiceClientConfiguration.builder().region(Region.of(region)).overrideConfiguration(
            ClientOverrideConfiguration.builder().retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                .build(),
        ).build()
    override fun describeKey(request: DescribeKeyRequest): DescribeKeyResponse {
        described.update { it + request.keyId() }
        if (request.keyId().startsWith("alias/") && !alias.peek()) throw NotFoundException.builder().build()
        if (region == SECONDARY && !replicated.peek()) throw NotFoundException.builder().build()
        return DescribeKeyResponse.builder().keyMetadata(publicMetadata(region, replicas = replicated.peek())).build()
    }
    override fun listKeys(request: ListKeysRequest): ListKeysResponse = ListKeysResponse.builder()
        .keys(KeyListEntry.builder().keyId(publicId).keyArn(publicArn(region)).build()).truncated(false).build()
    override fun listResourceTags(request: ListResourceTagsRequest): ListResourceTagsResponse =
        ListResourceTagsResponse.builder().tags(Tag.builder().tagKey("surefold:proof").tagValue("disposable").build())
            .truncated(false).build()
    override fun generateDataKey(request: GenerateDataKeyRequest): GenerateDataKeyResponse {
        dataRoots.update { it + request.keyId() }
        contexts.update { it + request.encryptionContext() }
        return GenerateDataKeyResponse.builder().keyId(publicArn(region))
            .plaintext(SdkBytes.fromByteArray(publicBytes())).ciphertextBlob(SdkBytes.fromByteArray(byteArrayOf(9)))
            .build()
    }
    override fun encrypt(request: EncryptRequest): EncryptResponse {
        dataRoots.update { it + request.keyId() }
        contexts.update { it + request.encryptionContext() }
        return EncryptResponse.builder().keyId(publicArn(region))
            .ciphertextBlob(SdkBytes.fromByteArray(byteArrayOf(9))).build()
    }
    override fun decrypt(request: DecryptRequest): DecryptResponse {
        dataRoots.update { it + request.keyId() }
        contexts.update { it + request.encryptionContext() }
        return DecryptResponse.builder().keyId(publicArn(region))
            .plaintext(SdkBytes.fromByteArray(publicBytes())).build()
    }
    override fun createKey(request: CreateKeyRequest): CreateKeyResponse {
        mutations.update { it + "create" }
        return CreateKeyResponse.builder().keyMetadata(publicMetadata(PRIMARY, replicas = false)).build()
    }
    override fun createAlias(request: CreateAliasRequest): CreateAliasResponse {
        mutations.update { it + "alias" }
        alias.update { true }
        return CreateAliasResponse.builder().build()
    }
    override fun enableKeyRotation(request: EnableKeyRotationRequest): EnableKeyRotationResponse {
        mutations.update { it + "rotation" }
        return EnableKeyRotationResponse.builder().build()
    }
    override fun replicateKey(request: ReplicateKeyRequest): ReplicateKeyResponse {
        mutations.update { it + "replicate" }
        replicated.update { true }
        return ReplicateKeyResponse.builder().replicaKeyMetadata(publicMetadata(SECONDARY, state = "Creating")).build()
    }
    override fun scheduleKeyDeletion(request: ScheduleKeyDeletionRequest): ScheduleKeyDeletionResponse {
        mutations.update { it + "delete" }
        return ScheduleKeyDeletionResponse.builder().keyId(publicArn(region)).keyState("PendingDeletion")
            .deletionDate(proofDate).build()
    }
}
