package io.github.matthewjones372.surefold.tenancy.crypto.aws

import dev.surefold.Shared
import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import io.github.matthewjones372.surefold.tenancy.crypto.KeyFailureCode
import io.github.matthewjones372.surefold.tenancy.crypto.KeyOperation
import io.github.matthewjones372.surefold.tenancy.crypto.KeyOperationFailure
import io.github.matthewjones372.surefold.tenancy.crypto.RootCreation
import io.github.matthewjones372.surefold.tenancy.crypto.RootKey
import io.github.matthewjones372.surefold.tenancy.crypto.RootState
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.retries.StandardRetryStrategy
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.KmsServiceClientConfiguration
import software.amazon.awssdk.services.kms.model.AlreadyExistsException
import software.amazon.awssdk.services.kms.model.DescribeKeyRequest
import software.amazon.awssdk.services.kms.model.DescribeKeyResponse
import software.amazon.awssdk.services.kms.model.KeyMetadata
import software.amazon.awssdk.services.kms.model.ListResourceTagsRequest
import software.amazon.awssdk.services.kms.model.ListResourceTagsResponse
import software.amazon.awssdk.services.kms.model.MultiRegionConfiguration
import software.amazon.awssdk.services.kms.model.MultiRegionKey
import software.amazon.awssdk.services.kms.model.NotFoundException
import software.amazon.awssdk.services.kms.model.ReplicateKeyRequest
import software.amazon.awssdk.services.kms.model.ReplicateKeyResponse
import software.amazon.awssdk.services.kms.model.Tag
import java.util.concurrent.CancellationException

object KmsReplicaSuite : Suite() {
    init {
        test("replication sends explicit regional settings through a single attempt primary client") {
            val world = ReplicaWorld(existing = false)
            val result = world.create().value()
            result.location.root shouldBe RootKey(REPLICA_ARN)
            result.state shouldBe RootState.Creating
            val request = world.primary.calls.peek().filterIsInstance<ReplicateKeyRequest>().single()
            request.keyId() shouldBe PRIMARY_ARN
            request.replicaRegion() shouldBe REPLICA_REGION
            request.policy() shouldBe "regional policy"
            request.description() shouldBe "regional description"
            request.tags().associate { it.tagKey() to it.tagValue() } shouldBe mapOf("owner" to "opaque")
            request.bypassPolicyLockoutSafetyCheck() shouldBe null
            world.replica.calls.peek().filterIsInstance<ReplicateKeyRequest>() shouldBe emptyList()
        }
        test("real SDK sends ReplicateKey exactly once when the mutation reply is lost or fails") {
            for (lost in listOf(true, false)) {
                val http = CreationWire(lost)
                KmsClient.builder().region(Region.of(PRIMARY_REGION)).httpClient(http)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                    .overrideConfiguration(
                        ClientOverrideConfiguration.builder().retryStrategy(
                            StandardRetryStrategy.builder().maxAttempts(1).build(),
                        ).build(),
                    ).build().use { client ->
                        val world = ReplicaWorld(existing = false)
                        replicaSubject(
                            mapOf(PRIMARY_REGION to world.primary, REPLICA_REGION to world.replica),
                            mapOf(PRIMARY_REGION to client),
                        ).createReplica(RootKey(PRIMARY_ARN), replicaRequest())
                            .failure().code shouldBe KeyFailureCode.UncertainMutation
                    }
                http.targets.peek() shouldBe listOf("TrentService.ReplicateKey")
                val body = http.bodies.peek().single()
                body.contains("\"KeyId\":\"$PRIMARY_ARN\"") shouldBe true
                body.contains("\"ReplicaRegion\":\"$REPLICA_REGION\"") shouldBe true
                body.contains("\"Policy\":\"regional policy\"") shouldBe true
                body.contains("\"Description\":\"regional description\"") shouldBe true
                body.contains("\"Tags\":[{\"TagKey\":\"owner\",\"TagValue\":\"opaque\"}]") shouldBe true
            }
        }
        test("existing replicas require complete primary relationship and full supplied tag subset") {
            val world = ReplicaWorld()
            world.create().value().state shouldBe RootState.Enabled
            world.primary.calls.peek().filterIsInstance<ReplicateKeyRequest>() shouldBe emptyList()
            world.replica.calls.peek().filterIsInstance<ListResourceTagsRequest>().map { it.marker() } shouldBe
                listOf(null, "second")
            world.inspect().value().location.root shouldBe RootKey(REPLICA_ARN)
        }
        test("invalid identities regions and blank policies fail before provider calls") {
            for (root in listOf(RootKey("alias/root"), RootKey("invalid"))) {
                val world = ReplicaWorld()
                world.create(root).failure().code shouldBe KeyFailureCode.InvalidInput
                world.primary.calls.peek() shouldBe emptyList()
            }
            for (request in listOf(
                replicaRequest(""), replicaRequest("eu-west-2"), replicaRequest("cn-north-1"),
                RootCreation(REPLICA_REGION, " ", "", emptyMap()),
            )) {
                val world = ReplicaWorld()
                world.create(request = request).failure().code shouldBe KeyFailureCode.InvalidInput
                world.primary.calls.peek() shouldBe emptyList()
            }
            val world = ReplicaWorld()
            world.inspect(replica = RootKey("bad")).failure().code shouldBe KeyFailureCode.InvalidInput
            world.primary.calls.peek() shouldBe emptyList()
        }
        test("single region and replica sources cannot authorize replication") {
            val replicaSource = primaryMetadata().toBuilder().multiRegionConfiguration(
                MultiRegionConfiguration.builder().multiRegionKeyType("REPLICA").primaryKey(related("us-east-1"))
                    .replicaKeys(related(PRIMARY_REGION)).build(),
            ).build()
            val world = ReplicaWorld(source = { replicaSource })
            world.create().failure().code shouldBe KeyFailureCode.Refused
            world.primary.calls.peek().filterIsInstance<ReplicateKeyRequest>() shouldBe emptyList()
            val id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            val singleArn = PRIMARY_ARN.replace(MRK, id)
            val single = ReplicaWorld(source = {
                primaryMetadata().toBuilder().arn(singleArn).keyId(id).multiRegion(false)
                    .multiRegionConfiguration(null as MultiRegionConfiguration?).build()
            })
            single.create(RootKey(singleArn)).failure().code shouldBe KeyFailureCode.Refused
            single.primary.calls.peek().filterIsInstance<ReplicateKeyRequest>() shouldBe emptyList()
        }
        test("source primary states preserve pending versus refused distinctions") {
            for ((state, code) in listOf(
                "Creating" to KeyFailureCode.Unavailable, "Updating" to KeyFailureCode.Unavailable,
                "Unavailable" to KeyFailureCode.Unavailable, "future" to KeyFailureCode.Unavailable,
                "Disabled" to KeyFailureCode.Refused, "PendingDeletion" to KeyFailureCode.Refused,
                "PendingReplicaDeletion" to KeyFailureCode.Refused,
            )) {
                val world = ReplicaWorld(source = { primaryMetadata().toBuilder().keyState(state).build() })
                world.create().failure() shouldBe KeyOperationFailure(KeyOperation.CreateReplica, code)
                world.primary.calls.peek().filterIsInstance<ReplicateKeyRequest>() shouldBe emptyList()
            }
        }
        test("inspection treats Creating as pending and refuses disabled replicas") {
            for ((state, code) in listOf(
                "Creating" to KeyFailureCode.Unavailable, "Updating" to KeyFailureCode.Unavailable,
                "future" to KeyFailureCode.Unavailable, "Unavailable" to KeyFailureCode.Unavailable,
                "Disabled" to KeyFailureCode.Refused, "PendingDeletion" to KeyFailureCode.Refused,
                "PendingReplicaDeletion" to KeyFailureCode.Refused,
            )) {
                val world = ReplicaWorld(local = { replicaMetadata().toBuilder().keyState(state).build() })
                world.inspect().failure() shouldBe KeyOperationFailure(KeyOperation.InspectReplica, code)
                if (state == "Creating") world.create().value().state shouldBe RootState.Creating
            }
        }
        test("every replica identity component and primary topology must agree") {
            for (metadata in listOf(
                replicaMetadata().toBuilder().awsAccountId("999999999999").build(),
                replicaMetadata().toBuilder().arn(REPLICA_ARN.replace("aws:", "aws-cn:")).build(),
                replicaMetadata().toBuilder().arn(REPLICA_ARN.replace(REPLICA_REGION, "us-east-2")).build(),
                replicaMetadata().toBuilder().keyId("mrk-${"b".repeat(32)}").build(),
                replicaMetadata().toBuilder().multiRegionConfiguration(topology(false, "us-west-2")).build(),
                replicaMetadata().toBuilder().multiRegionConfiguration(null as MultiRegionConfiguration?).build(),
                replicaMetadata().toBuilder().multiRegionConfiguration(topology(true)).build(),
            )) {
                val world = ReplicaWorld(local = { metadata })
                world.create().isLeft() shouldBe true
                world.inspect().isLeft() shouldBe true
                world.primary.calls.peek().filterIsInstance<ReplicateKeyRequest>() shouldBe emptyList()
            }
            val world = ReplicaWorld(source = { primaryMetadata(false) })
            world.create().failure().code shouldBe KeyFailureCode.Conflict
            for (arn in listOf(
                REPLICA_ARN.replace(ACCOUNT, "999999999999"), PRIMARY_ARN,
                REPLICA_ARN.replace(MRK, "mrk-${"b".repeat(32)}"),
            )) {
                val invalid = ReplicaWorld()
                invalid.inspect(replica = RootKey(arn)).failure().code shouldBe KeyFailureCode.Conflict
                invalid.primary.calls.peek() shouldBe emptyList()
            }
        }
        test("tag conflicts and malformed later pages never accept existing replicas") {
            for (page in listOf(
                ListResourceTagsResponse.builder().tags(tag("owner", "wrong")).truncated(false).build(),
                ListResourceTagsResponse.builder().tags(tag("other", "x")).truncated(false).build(),
                ListResourceTagsResponse.builder().tags(tag("owner", "opaque")).truncated(true).build(),
            )) {
                val world = ReplicaWorld(tags = { marker ->
                    if (marker == null) ListResourceTagsResponse.builder().tags(tag("extra", "retained"))
                        .truncated(true).nextMarker("second").build() else page
                })
                world.create().isLeft() shouldBe true
                world.primary.calls.peek().filterIsInstance<ReplicateKeyRequest>() shouldBe emptyList()
            }
        }
        test("missing unavailable and incomplete observations never trigger unsafe replication") {
            for (source in listOf<() -> KeyMetadata?>(
                { null }, { throw NotFoundException.builder().build() }, { throw SdkClientException.create("private") },
            )) {
                val world = ReplicaWorld(source = source)
                world.create().isLeft() shouldBe true
                world.primary.calls.peek().filterIsInstance<ReplicateKeyRequest>() shouldBe emptyList()
            }
            for (local in listOf<() -> KeyMetadata?>(
                { null }, { throw SdkClientException.create("private") },
            )) {
                val world = ReplicaWorld(local = local)
                world.create().isLeft() shouldBe true
                world.primary.calls.peek().filterIsInstance<ReplicateKeyRequest>() shouldBe emptyList()
            }
        }
        test("known replica missing from local observation does not authorize another mutation") {
            val world = ReplicaWorld(local = { throw NotFoundException.builder().build() })
            val result = world.create()
            (result as? Either.Left)?.value shouldBe
                KeyOperationFailure(KeyOperation.CreateReplica, KeyFailureCode.NotObserved)
            world.primary.calls.peek().filterIsInstance<ReplicateKeyRequest>() shouldBe emptyList()
        }
        test("lost replies and malformed post mutation success remain uncertain") {
            for (mutation in listOf<() -> ReplicateKeyResponse>(
                { throw SdkClientException.create("private policy") },
                { ReplicateKeyResponse.builder().build() },
                { ReplicateKeyResponse.builder().replicaKeyMetadata(primaryMetadata()).build() },
                {
                    ReplicateKeyResponse.builder()
                        .replicaKeyMetadata(replicaMetadata().toBuilder().keyState("future").build()).build()
                },
            )) {
                val world = ReplicaWorld(existing = false, mutation = mutation)
                world.create().failure() shouldBe
                    KeyOperationFailure(KeyOperation.CreateReplica, KeyFailureCode.UncertainMutation)
                world.primary.calls.peek().filterIsInstance<ReplicateKeyRequest>().size shouldBe 1
            }
        }
        test("already exists races reread actual replica and required tags before acceptance") {
            val reads = Shared(0)
            val world = ReplicaWorld(source = { primaryMetadata(reads.peek() > 0) }, local = {
                reads.update { it + 1 }
                if (reads.peek() == 1) throw NotFoundException.builder().build()
                replicaMetadata()
            }, mutation = { throw AlreadyExistsException.builder().build() })
            world.create().value().state shouldBe RootState.Enabled
            reads.peek() shouldBe 2
            world.primary.calls.peek().filterIsInstance<ReplicateKeyRequest>().size shouldBe 1
            for (missing in listOf(false, true)) {
                val count = Shared(0)
                val conflict = ReplicaWorld(source = { primaryMetadata(count.peek() > 0) }, local = {
                    count.update { it + 1 }
                    if (count.peek() == 1 || missing) throw NotFoundException.builder().build()
                    replicaMetadata().toBuilder().awsAccountId("999999999999").build()
                }, mutation = { throw AlreadyExistsException.builder().build() })
                conflict.create().failure().code shouldBe
                    if (missing) KeyFailureCode.NotObserved else KeyFailureCode.Conflict
            }
        }
        test("cancellation in already exists propagates before race readback") {
            for (cause in listOf(CancellationException("cancel"), LinkageError("fatal"))) {
                val world = ReplicaWorld(existing = false, mutation = {
                    throw AlreadyExistsException.builder().cause(cause).build()
                })
                if (cause is CancellationException) {
                    shouldThrow<CancellationException>({ world.create() }) { it shouldBe cause }
                } else {
                    shouldThrow<LinkageError>({ world.create() }) { it shouldBe cause }
                }
                world.replica.calls.peek().filterIsInstance<DescribeKeyRequest>().size shouldBe 1
            }
        }
        test("creation client guards do not restrict normal observation clients") {
            for (attempts in listOf(0, 2)) {
                val world = ReplicaWorld(attempts = attempts, existing = false)
                world.create().failure().code shouldBe KeyFailureCode.Refused
                world.primary.calls.peek().filterIsInstance<ReplicateKeyRequest>() shouldBe emptyList()
            }
            val world = ReplicaWorld()
            replicaSubject(emptyMap(), emptyMap()).createReplica(RootKey(PRIMARY_ARN), replicaRequest())
                .failure().code shouldBe KeyFailureCode.Unavailable
            world.create(request = RootCreation(REPLICA_REGION, "policy", "", emptyMap())).isRight() shouldBe true
            val normal = ReplicaStub(PRIMARY_REGION, { primaryMetadata(false) }, {
                throw AssertionError("observation client received replication")
            }, attempts = 3)
            val separate = ReplicaWorld(existing = false)
            replicaSubject(
                mapOf(PRIMARY_REGION to normal, REPLICA_REGION to separate.replica),
                mapOf(PRIMARY_REGION to separate.primary),
            ).createReplica(RootKey(PRIMARY_ARN), replicaRequest()).value().state shouldBe RootState.Creating
            separate.primary.calls.peek().filterIsInstance<ReplicateKeyRequest>().size shouldBe 1
            normal.calls.peek().filterIsInstance<DescribeKeyRequest>().size shouldBe 1
        }
    }
}

private const val PRIMARY_REGION = "eu-west-2"
private const val REPLICA_REGION = "eu-west-1"
private const val ACCOUNT = "123456789012"
private val MRK = "mrk-${"a".repeat(32)}"
private val PRIMARY_ARN = "arn:aws:kms:$PRIMARY_REGION:$ACCOUNT:key/$MRK"
private val REPLICA_ARN = "arn:aws:kms:$REPLICA_REGION:$ACCOUNT:key/$MRK"
private fun tag(key: String, value: String) = Tag.builder().tagKey(key).tagValue(value).build()
private fun replicaRequest(region: String = REPLICA_REGION) =
    RootCreation(region, "regional policy", "regional description", mapOf("owner" to "opaque"))
private fun related(region: String) = MultiRegionKey.builder().arn("arn:aws:kms:$region:$ACCOUNT:key/$MRK")
    .region(region).build()
private fun topology(primary: Boolean, source: String = PRIMARY_REGION, existing: Boolean = true) =
    MultiRegionConfiguration.builder().multiRegionKeyType(if (primary) "PRIMARY" else "REPLICA")
        .primaryKey(related(source)).replicaKeys(if (existing) listOf(related(REPLICA_REGION)) else emptyList()).build()
private fun primaryMetadata(existing: Boolean = true) = KeyMetadata.builder().arn(PRIMARY_ARN).keyId(MRK)
    .awsAccountId(ACCOUNT).multiRegion(true).keyState("Enabled").keySpec("SYMMETRIC_DEFAULT")
    .keyUsage("ENCRYPT_DECRYPT").multiRegionConfiguration(topology(true, existing = existing)).build()
private fun replicaMetadata(state: String = "Enabled") = primaryMetadata().toBuilder().arn(REPLICA_ARN)
    .keyState(state).multiRegionConfiguration(topology(false)).build()
private fun <A> Either<KeyOperationFailure, A>.value(): A = (this as Either.Right).value
private fun <A> Either<KeyOperationFailure, A>.failure(): KeyOperationFailure = (this as Either.Left).value

private class ReplicaWorld(
    existing: Boolean = true,
    source: () -> KeyMetadata? = { primaryMetadata(existing) },
    local: () -> KeyMetadata? = {
        if (!existing) throw NotFoundException.builder().build()
        replicaMetadata()
    },
    tags: ((String?) -> ListResourceTagsResponse)? = null,
    mutation: () -> ReplicateKeyResponse = {
        ReplicateKeyResponse.builder().replicaKeyMetadata(replicaMetadata("Creating")).build()
    },
    attempts: Int = 1,
) {
    val primary = ReplicaStub(PRIMARY_REGION, source, mutation, attempts = attempts)
    val replica = ReplicaStub(REPLICA_REGION, local, mutation, tags)
    private val subject = replicaSubject(
        mapOf(PRIMARY_REGION to primary, REPLICA_REGION to replica), mapOf(PRIMARY_REGION to primary),
    )
    fun create(primary: RootKey = RootKey(PRIMARY_ARN), request: RootCreation = replicaRequest()) =
        subject.createReplica(primary, request)
    fun inspect(primary: RootKey = RootKey(PRIMARY_ARN), replica: RootKey = RootKey(REPLICA_ARN)) =
        subject.inspectReplica(primary, replica)
}

private class ReplicaStub(
    private val region: String,
    private val describe: () -> KeyMetadata?,
    private val mutate: () -> ReplicateKeyResponse,
    private val tags: ((String?) -> ListResourceTagsResponse)? = null,
    private val attempts: Int = 1,
) : KmsClient {
    val calls = Shared(emptyList<Any>())
    override fun serviceName() = "kms"
    override fun close() = Unit
    override fun serviceClientConfiguration(): KmsServiceClientConfiguration = KmsServiceClientConfiguration.builder()
        .region(Region.of(region)).overrideConfiguration(
            if (attempts == 0) ClientOverrideConfiguration.builder().build() else ClientOverrideConfiguration.builder()
                .retryStrategy(StandardRetryStrategy.builder().maxAttempts(attempts).build()).build(),
        ).build()
    override fun describeKey(request: DescribeKeyRequest): DescribeKeyResponse {
        calls.update { it + request }
        return DescribeKeyResponse.builder().keyMetadata(describe()).build()
    }
    override fun replicateKey(request: ReplicateKeyRequest): ReplicateKeyResponse {
        calls.update { it + request }
        return mutate()
    }
    override fun listResourceTags(request: ListResourceTagsRequest): ListResourceTagsResponse {
        calls.update { it + request }
        return tags?.invoke(request.marker()) ?: if (request.marker() == null) {
            ListResourceTagsResponse.builder().tags(tag("extra", "retained"))
                .truncated(true).nextMarker("second").build()
        } else ListResourceTagsResponse.builder().tags(tag("owner", "opaque")).truncated(false).build()
    }
}

private fun replicaSubject(clients: Map<String, KmsClient>, creation: Map<String, KmsClient>) =
    KmsRootReplicas(KmsObservations(clients), creation)
