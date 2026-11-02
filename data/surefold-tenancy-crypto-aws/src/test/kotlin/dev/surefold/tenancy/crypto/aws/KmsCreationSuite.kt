package dev.surefold.tenancy.crypto.aws

import dev.surefold.Shared
import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import dev.surefold.tenancy.crypto.KeyFailureCode
import dev.surefold.tenancy.crypto.KeyOperation
import dev.surefold.tenancy.crypto.KeyOperationFailure
import dev.surefold.tenancy.crypto.RootCreation
import dev.surefold.tenancy.crypto.RootKey
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.HttpExecuteResponse
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpResponse
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.retries.StandardRetryStrategy
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.KmsServiceClientConfiguration
import software.amazon.awssdk.services.kms.model.AlreadyExistsException
import software.amazon.awssdk.services.kms.model.CreateAliasRequest
import software.amazon.awssdk.services.kms.model.CreateAliasResponse
import software.amazon.awssdk.services.kms.model.CreateKeyRequest
import software.amazon.awssdk.services.kms.model.CreateKeyResponse
import software.amazon.awssdk.services.kms.model.DescribeKeyRequest
import software.amazon.awssdk.services.kms.model.DescribeKeyResponse
import software.amazon.awssdk.services.kms.model.EnableKeyRotationRequest
import software.amazon.awssdk.services.kms.model.EnableKeyRotationResponse
import software.amazon.awssdk.services.kms.model.KeyMetadata
import software.amazon.awssdk.services.kms.model.KeySpec
import software.amazon.awssdk.services.kms.model.KeyUsageType
import software.amazon.awssdk.services.kms.model.MultiRegionConfiguration
import software.amazon.awssdk.services.kms.model.MultiRegionKey
import software.amazon.awssdk.services.kms.model.NotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException

object KmsCreationSuite : Suite() {
    init {
        test("primary creation uses explicit policy description tags and a single attempt without later mutations") {
            val client = CreationStub()
            val result = creation(client).createPrimary(creationRequest()).value()
            result.location.root shouldBe RootKey(CREATE_ARN)
            val request = client.calls.peek().single() as CreateKeyRequest
            request.policy() shouldBe "explicit policy"
            request.description() shouldBe "explicit description"
            request.tags().associate { it.tagKey() to it.tagValue() } shouldBe mapOf("owner" to "opaque")
            request.keySpec() shouldBe KeySpec.SYMMETRIC_DEFAULT
            request.keyUsage() shouldBe KeyUsageType.ENCRYPT_DECRYPT
            request.multiRegion() shouldBe true
        }
        test("blank explicit inputs and unsafe or unknown creation clients refuse before any mutation") {
            for (request in listOf(
                creationRequest(policy = " "),
                RootCreation("", "policy", "description", emptyMap()),
            )) {
                val client = CreationStub()
                creation(client).createPrimary(request).failure().code shouldBe KeyFailureCode.InvalidInput
                client.calls.peek() shouldBe emptyList()
            }
            for (configuration in listOf(null, ClientOverrideConfiguration.builder().build(), retryConfiguration(2))) {
                val client = CreationStub(configuration = configuration)
                creation(client).createPrimary(creationRequest()).failure().code shouldBe KeyFailureCode.Refused
                client.calls.peek() shouldBe emptyList()
            }
            val wrongRegion = CreationStub(region = "eu-west-1")
            creation(wrongRegion).createPrimary(creationRequest()).failure().code shouldBe KeyFailureCode.Conflict
            wrongRegion.calls.peek() shouldBe emptyList()
            KmsRootCreation(KmsObservations(emptyMap()), emptyMap()).createPrimary(creationRequest())
                .failure().code shouldBe KeyFailureCode.Unavailable
        }
        test("a mutation reply that cannot prove a symmetric multi region primary is uncertain") {
            for (metadata in listOf(
                null, createMetadata().toBuilder().keyId("wrong").build(),
                createMetadata().toBuilder().keySpec("RSA_2048").build(),
                createMetadata().toBuilder().keyUsage("SIGN_VERIFY").build(),
                createMetadata().toBuilder().multiRegionConfiguration(null as MultiRegionConfiguration?).build(),
                createMetadata().toBuilder().arn(CREATE_ARN.replace(CREATE_REGION, "eu-west-1")).build(),
                createMetadata().toBuilder().keyState("future").build(),
            )) {
                val client = CreationStub(created = metadata)
                creation(client).createPrimary(creationRequest()).failure() shouldBe
                    KeyOperationFailure(KeyOperation.CreatePrimary, KeyFailureCode.UncertainMutation)
                client.calls.peek().size shouldBe 1
            }
            val lost = CreationStub(create = { throw SdkClientException.create("private policy") })
            creation(lost).createPrimary(creationRequest()).failure().code shouldBe KeyFailureCode.UncertainMutation
            lost.calls.peek().size shouldBe 1
        }
        test("real SDK serializes CreateKey and makes one HTTP attempt on lost reply and server error") {
            for (lost in listOf(true, false)) {
                val http = CreationWire(lost)
                KmsClient.builder().region(Region.of(CREATE_REGION)).httpClient(http)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                    .overrideConfiguration(retryConfiguration(1)).build().use { client ->
                        client.serviceClientConfiguration().overrideConfiguration().retryStrategy().get()
                            .maxAttempts() shouldBe 1
                        val operations = KmsRootCreation(
                            KmsObservations(mapOf(CREATE_REGION to client)),
                            mapOf(CREATE_REGION to client),
                        )
                        operations.createPrimary(creationRequest()).failure().code shouldBe
                            KeyFailureCode.UncertainMutation
                    }
                http.bodies.peek().size shouldBe 1
                http.bodies.peek().single().contains("\"MultiRegion\":true") shouldBe true
                http.targets.peek() shouldBe listOf("TrentService.CreateKey")
            }
            val http = CreationWire(true)
            KmsClient.builder().region(Region.of(CREATE_REGION)).httpClient(http)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build().use { client ->
                    KmsRootCreation(KmsObservations(emptyMap()), mapOf(CREATE_REGION to client))
                        .createPrimary(creationRequest()).failure().code shouldBe KeyFailureCode.Refused
                }
            http.bodies.peek() shouldBe emptyList()
        }
        test("alias binding observes existing aliases and never reassigns conflicting roots") {
            val existing = CreationStub()
            creation(existing).bindAlias(RootKey(CREATE_ARN), "alias/explicit").isRight() shouldBe true
            existing.calls.peek().filterIsInstance<CreateAliasRequest>() shouldBe emptyList()
            val other = createMetadata().toBuilder().arn(CREATE_ARN.replace(CREATE_MRK, OTHER_MRK))
                .keyId(OTHER_MRK).multiRegionConfiguration(primaryTopology(OTHER_MRK)).build()
            val conflict = CreationStub(describe = { other })
            creation(conflict).bindAlias(RootKey(CREATE_ARN), "alias/explicit").failure().code shouldBe
                KeyFailureCode.Conflict
            conflict.calls.peek().size shouldBe 1
        }
        test("only missing aliases are bound and an already exists race is verified by readback") {
            for (raced in listOf(false, true)) {
                val reads = Shared(0)
                val client = CreationStub(describe = {
                    reads.update { it + 1 }
                    if (reads.peek() == 1) throw NotFoundException.builder().build()
                    createMetadata()
                }, alias = { if (raced) throw AlreadyExistsException.builder().build() })
                creation(client).bindAlias(RootKey(CREATE_ARN), "alias/explicit").isRight() shouldBe true
                val bound = client.calls.peek().filterIsInstance<CreateAliasRequest>().single()
                bound.targetKeyId() shouldBe CREATE_ARN
                bound.aliasName() shouldBe "alias/explicit"
                client.calls.peek().filterIsInstance<CreateKeyRequest>() shouldBe emptyList()
                reads.peek() shouldBe if (raced) 2 else 1
            }
        }
        test("invalid aliases never mutate and lost alias replies remain uncertain") {
            for (alias in listOf("root", "alias/aws/s3", "alias/", "alias/bad space", "alias/" + "x".repeat(300))) {
                val client = CreationStub()
                creation(client).bindAlias(RootKey(CREATE_ARN), alias).failure().code shouldBe
                    KeyFailureCode.InvalidInput
                client.calls.peek() shouldBe emptyList()
            }
            val client = CreationStub(
                describe = { throw NotFoundException.builder().build() },
                alias = { throw SdkClientException.create("private alias") },
            )
            creation(client).bindAlias(RootKey(CREATE_ARN), "alias/explicit").failure().code shouldBe
                KeyFailureCode.UncertainMutation
        }
        test("rotation describes and enables only an enabled known multi region primary") {
            val client = CreationStub()
            creation(client).enableRotation(RootKey(CREATE_ARN)).isRight() shouldBe true
            client.calls.peek().filterIsInstance<EnableKeyRotationRequest>().single().keyId() shouldBe CREATE_ARN
            for ((state, code) in listOf(
                "Creating" to KeyFailureCode.Unavailable,
                "Updating" to KeyFailureCode.Unavailable, "Unavailable" to KeyFailureCode.Unavailable,
                "future" to KeyFailureCode.Unavailable, "Disabled" to KeyFailureCode.Refused,
                "PendingDeletion" to KeyFailureCode.Refused, "PendingReplicaDeletion" to KeyFailureCode.Refused,
            )) {
                val invalid = CreationStub(describe = { createMetadata().toBuilder().keyState(state).build() })
                creation(invalid).enableRotation(RootKey(CREATE_ARN)).failure().code shouldBe code
                invalid.calls.peek().size shouldBe 1
            }
            val replica = CreationStub(describe = {
                createMetadata().toBuilder().multiRegionConfiguration(
                    MultiRegionConfiguration.builder()
                        .multiRegionKeyType("REPLICA").primaryKey(relation("eu-west-1"))
                        .replicaKeys(relation(CREATE_REGION)).build(),
                ).build()
            })
            creation(replica).enableRotation(RootKey(CREATE_ARN)).failure().code shouldBe KeyFailureCode.Refused
            replica.calls.peek().size shouldBe 1
        }
        test("separate creation clients do not restrict safe observation client retries") {
            val normal = CreationStub(configuration = retryConfiguration(3))
            val single = CreationStub()
            val operations = KmsRootCreation(
                KmsObservations(mapOf(CREATE_REGION to normal)),
                mapOf(CREATE_REGION to single),
            )
            operations.createPrimary(creationRequest()).isRight() shouldBe true
            operations.enableRotation(RootKey(CREATE_ARN)).isRight() shouldBe true
            single.calls.peek().filterIsInstance<CreateKeyRequest>().size shouldBe 1
            normal.calls.peek().filterIsInstance<EnableKeyRotationRequest>().size shouldBe 1
            normal.calls.peek().filterIsInstance<CreateKeyRequest>() shouldBe emptyList()
        }
        test("an alias race with conflicting or absent readback never becomes success") {
            for (missing in listOf(false, true)) {
                val reads = Shared(0)
                val client = CreationStub(describe = {
                    reads.update { it + 1 }
                    if (reads.peek() == 1 || missing) throw NotFoundException.builder().build()
                    createMetadata().toBuilder().arn(CREATE_ARN.replace(CREATE_MRK, OTHER_MRK))
                        .keyId(OTHER_MRK).multiRegionConfiguration(primaryTopology(OTHER_MRK)).build()
                }, alias = { throw AlreadyExistsException.builder().build() })
                val result = creation(client).bindAlias(RootKey(CREATE_ARN), "alias/explicit")
                result.failure().operation shouldBe KeyOperation.BindAlias
                result.failure().code shouldBe if (missing) KeyFailureCode.NotObserved else KeyFailureCode.Conflict
                client.calls.peek().filterIsInstance<CreateAliasRequest>().size shouldBe 1
                reads.peek() shouldBe 2
            }
        }
        test("unresolved alias observations and rotation mutations preserve their operation and certainty") {
            val unavailable = CreationStub(describe = { throw SdkClientException.create("private") })
            creation(unavailable).bindAlias(RootKey(CREATE_ARN), "alias/explicit").failure() shouldBe
                KeyOperationFailure(KeyOperation.BindAlias, KeyFailureCode.Unavailable)
            unavailable.calls.peek().filterIsInstance<CreateAliasRequest>() shouldBe emptyList()
            creation(unavailable).enableRotation(RootKey(CREATE_ARN)).failure() shouldBe
                KeyOperationFailure(KeyOperation.EnableRotation, KeyFailureCode.Unavailable)
            val lost = CreationStub(rotation = { throw SdkClientException.create("private") })
            creation(lost).enableRotation(RootKey(CREATE_ARN)).failure() shouldBe
                KeyOperationFailure(KeyOperation.EnableRotation, KeyFailureCode.UncertainMutation)
            for (root in listOf(RootKey("alias/root"), RootKey("invalid"))) {
                val client = CreationStub()
                creation(client).bindAlias(root, "alias/explicit").failure().code shouldBe KeyFailureCode.InvalidInput
                creation(client).enableRotation(root).failure().code shouldBe KeyFailureCode.InvalidInput
                client.calls.peek() shouldBe emptyList()
            }
        }
        test("an explicitly empty description is sent unchanged") {
            val client = CreationStub()
            creation(client).createPrimary(creationRequest(description = "")).isRight() shouldBe true
            client.calls.peek().filterIsInstance<CreateKeyRequest>().single().description() shouldBe ""
        }
        test("an already exists reply with cancellation or fatal cause propagates before alias readback") {
            for (cause in listOf(CancellationException("cancel"), LinkageError("fatal"))) {
                val reads = Shared(0)
                val client = CreationStub(describe = {
                    reads.update { it + 1 }
                    if (reads.peek() == 1) throw NotFoundException.builder().build()
                    createMetadata()
                }, alias = { throw AlreadyExistsException.builder().cause(cause).build() })
                if (cause is CancellationException) {
                    shouldThrow<CancellationException>({
                        creation(client).bindAlias(RootKey(CREATE_ARN), "alias/explicit")
                    }) { it shouldBe cause }
                } else {
                    shouldThrow<LinkageError>({
                        creation(client).bindAlias(RootKey(CREATE_ARN), "alias/explicit")
                    }) { it shouldBe cause }
                }
                reads.peek() shouldBe 1
            }
        }
        test("programmer errors and cancellation propagate from creation") {
            for (error in listOf(IllegalStateException("bug"), CancellationException("cancel"))) {
                val client = CreationStub(create = { throw error })
                shouldThrow<RuntimeException> { creation(client).createPrimary(creationRequest()) }
            }
        }
    }
}

private const val CREATE_REGION = "eu-west-2"
private const val CREATE_ACCOUNT = "123456789012"
private val CREATE_MRK = "mrk-${"a".repeat(32)}"
private val OTHER_MRK = "mrk-${"b".repeat(32)}"
private val CREATE_ARN = "arn:aws:kms:$CREATE_REGION:$CREATE_ACCOUNT:key/$CREATE_MRK"
private fun relation(region: String, id: String = CREATE_MRK) = MultiRegionKey.builder()
    .arn("arn:aws:kms:$region:$CREATE_ACCOUNT:key/$id").region(region).build()
private fun primaryTopology(id: String = CREATE_MRK) = MultiRegionConfiguration.builder().multiRegionKeyType("PRIMARY")
    .primaryKey(relation(CREATE_REGION, id)).replicaKeys(emptyList()).build()
private fun createMetadata() = KeyMetadata.builder().arn(CREATE_ARN).keyId(CREATE_MRK).awsAccountId(CREATE_ACCOUNT)
    .keyState("Enabled").keySpec("SYMMETRIC_DEFAULT").keyUsage("ENCRYPT_DECRYPT").multiRegion(true)
    .multiRegionConfiguration(primaryTopology()).build()
private fun creationRequest(policy: String = "explicit policy", description: String = "explicit description") =
    RootCreation(CREATE_REGION, policy, description, mapOf("owner" to "opaque"))
private fun retryConfiguration(attempts: Int) = ClientOverrideConfiguration.builder()
    .retryStrategy(StandardRetryStrategy.builder().maxAttempts(attempts).build()).build()
private fun creation(client: CreationStub) = KmsRootCreation(
    KmsObservations(mapOf(CREATE_REGION to client)),
    mapOf(CREATE_REGION to client),
)
private fun <A> Either<KeyOperationFailure, A>.value(): A = (this as Either.Right).value
private fun <A> Either<KeyOperationFailure, A>.failure(): KeyOperationFailure = (this as Either.Left).value

private class CreationStub(
    private val region: String = CREATE_REGION,
    private val configuration: ClientOverrideConfiguration? = retryConfiguration(1),
    private val created: KeyMetadata? = createMetadata(),
    private val create: (() -> CreateKeyResponse)? = null,
    private val describe: () -> KeyMetadata = { createMetadata() },
    private val alias: () -> Unit = {},
    private val rotation: () -> Unit = {},
) : KmsClient {
    val calls = Shared(emptyList<Any>())
    override fun serviceName() = "kms"
    override fun close() = Unit
    override fun serviceClientConfiguration(): KmsServiceClientConfiguration {
        val builder = KmsServiceClientConfiguration.builder().region(Region.of(region))
        if (configuration != null) builder.overrideConfiguration(configuration)
        return builder.build()
    }
    override fun createKey(request: CreateKeyRequest): CreateKeyResponse {
        calls.update { it + request }
        return create?.invoke() ?: CreateKeyResponse.builder().keyMetadata(created).build()
    }
    override fun describeKey(request: DescribeKeyRequest): DescribeKeyResponse {
        calls.update { it + request }
        return DescribeKeyResponse.builder().keyMetadata(describe()).build()
    }
    override fun createAlias(request: CreateAliasRequest): CreateAliasResponse {
        calls.update { it + request }
        alias()
        return CreateAliasResponse.builder().build()
    }
    override fun enableKeyRotation(request: EnableKeyRotationRequest): EnableKeyRotationResponse {
        calls.update { it + request }
        rotation()
        return EnableKeyRotationResponse.builder().build()
    }
}

internal class CreationWire(private val lost: Boolean) : SdkHttpClient {
    val bodies = Shared(emptyList<String>())
    val targets = Shared(emptyList<String>())
    override fun close() = Unit
    override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest = object : ExecutableHttpRequest {
        override fun abort() = Unit
        override fun call(): HttpExecuteResponse {
            bodies.update {
                it + request.contentStreamProvider().get().newStream().use { body ->
                    body.readAllBytes().decodeToString()
                }
            }
            targets.update { it + request.httpRequest().firstMatchingHeader("X-Amz-Target").get() }
            if (lost) throw IOException("lost reply")
            return HttpExecuteResponse.builder().response(
                SdkHttpResponse.builder().statusCode(500)
                    .putHeader("Content-Type", "application/x-amz-json-1.1").build(),
            )
                .responseBody(AbortableInputStream.create("{\"__type\":\"KMSInternalException\"}".byteInputStream()))
                .build()
        }
    }
}
