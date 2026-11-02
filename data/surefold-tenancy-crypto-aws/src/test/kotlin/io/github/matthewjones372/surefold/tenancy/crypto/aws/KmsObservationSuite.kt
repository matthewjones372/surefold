package io.github.matthewjones372.surefold.tenancy.crypto.aws

import dev.surefold.Shared
import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.tenancy.crypto.KeyFailureCode
import io.github.matthewjones372.surefold.tenancy.crypto.KeyOperationFailure
import io.github.matthewjones372.surefold.tenancy.crypto.RootKey
import io.github.matthewjones372.surefold.tenancy.crypto.RootState
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.KmsServiceClientConfiguration
import software.amazon.awssdk.services.kms.model.DescribeKeyRequest
import software.amazon.awssdk.services.kms.model.DescribeKeyResponse
import software.amazon.awssdk.services.kms.model.KeyListEntry
import software.amazon.awssdk.services.kms.model.KeyMetadata
import software.amazon.awssdk.services.kms.model.ListKeysRequest
import software.amazon.awssdk.services.kms.model.ListKeysResponse
import software.amazon.awssdk.services.kms.model.ListResourceTagsRequest
import software.amazon.awssdk.services.kms.model.ListResourceTagsResponse
import software.amazon.awssdk.services.kms.model.MultiRegionConfiguration
import software.amazon.awssdk.services.kms.model.MultiRegionKey
import software.amazon.awssdk.services.kms.model.Tag
import java.time.Instant

object KmsObservationSuite : Suite() {
    init {
        test("single Region metadata retains identity state and provider deletion date") {
            val date = Instant.parse("2026-11-10T00:00:00Z")
            val client = ObservationStub(metadata = single().toBuilder().deletionDate(date).build())
            val observed = KmsObservations(mapOf(REGION to client)).describe(RootKey(ARN)).value()
            observed.metadata.location.root shouldBe RootKey(ARN)
            observed.metadata.location.account shouldBe ACCOUNT
            observed.metadata.location.partition shouldBe "aws"
            observed.metadata.location.region shouldBe REGION
            observed.metadata.primary shouldBe null
            observed.metadata.replicas shouldBe emptyList()
            observed.provider.deletionDate() shouldBe date
            for ((provider, state) in listOf(
                "Creating" to RootState.Creating, "Enabled" to RootState.Enabled,
                "Disabled" to RootState.Disabled, "Updating" to RootState.Updating,
                "PendingDeletion" to RootState.PendingDeletion,
                "PendingReplicaDeletion" to RootState.PendingReplicaDeletion,
                "Unavailable" to RootState.Unavailable, "PendingImport" to RootState.Unknown,
                "future" to RootState.Unknown,
            )) {
                val stub = ObservationStub(metadata = single().toBuilder().keyState(provider).build())
                KmsObservations(mapOf(REGION to stub)).describe(RootKey(ARN)).value().metadata.state shouldBe state
            }
        }

        test("regional clients are snapshotted and their actual SDK Region is verified before calls") {
            val client = ObservationStub()
            val configured = mutableMapOf<String, KmsClient>(REGION to client)
            val observations = KmsObservations(configured)
            configured.clear()
            observations.describe(RootKey(ARN)).isRight() shouldBe true
            KmsObservations(emptyMap()).describe(RootKey(ARN)).code() shouldBe KeyFailureCode.Unavailable
            val wrong = ObservationStub(region = OTHER)
            KmsObservations(mapOf(REGION to wrong)).describe(RootKey(ARN)).code() shouldBe KeyFailureCode.Conflict
            wrong.calls.peek() shouldBe emptyList()
            observations.describe(RootKey("alias/root")).code() shouldBe KeyFailureCode.InvalidInput
        }

        test("missing or contradictory metadata never becomes a successful observation") {
            for (metadata in listOf(
                single().toBuilder().arn(null as String?).build(),
                single().toBuilder().keyId(null as String?).build(),
                single().toBuilder().awsAccountId(null as String?).build(),
                single().toBuilder().keyState(null as String?).build(),
                single().toBuilder().multiRegion(null as Boolean?).build(),
            )) {
                KmsObservations(mapOf(REGION to ObservationStub(metadata = metadata)))
                    .describe(RootKey(ARN)).code() shouldBe KeyFailureCode.IncompleteEvidence
            }
            for (metadata in listOf(
                single().toBuilder().arn(ARN.replace(REGION, OTHER)).build(),
                single().toBuilder().keyId(MRK).build(),
                single().toBuilder().awsAccountId("999999999999").build(),
                single().toBuilder().multiRegion(true).build(),
                multi().toBuilder().multiRegion(false).build(),
            )) {
                KmsObservations(mapOf(REGION to ObservationStub(metadata = metadata)))
                    .describe(RootKey(ARN)).isLeft() shouldBe true
            }
            val absent = ObservationStub(metadata = null)
            KmsObservations(mapOf(REGION to absent)).describe(RootKey(ARN)).code() shouldBe
                KeyFailureCode.IncompleteEvidence
        }

        test("multi Region observations retain primary self and replica self in the complete topology") {
            for ((region, role) in listOf(REGION to "PRIMARY", OTHER to "REPLICA")) {
                val client = ObservationStub(region, multi(region, role))
                val metadata = KmsObservations(mapOf(region to client))
                    .describe(RootKey(mrkArn(region))).value().metadata
                metadata.primary!!.root shouldBe RootKey(mrkArn(REGION))
                metadata.replicas.map { it.root } shouldBe listOf(RootKey(mrkArn(OTHER)))
            }
        }

        test("multi Region topology rejects incomplete contradictory and duplicate evidence") {
            val config = topology()
            for (broken in listOf(
                withTopology(null as MultiRegionConfiguration?),
                withTopology(config.toBuilder().primaryKey(null as MultiRegionKey?).build()),
                withTopology(config.toBuilder().replicaKeys(null as List<MultiRegionKey>?).build()),
                withTopology(config.toBuilder().multiRegionKeyType("future").build()),
                withTopology(topology(primary = relation(OTHER))),
                withTopology(topology(replicas = listOf(relation(OTHER), relation(OTHER)))),
                withTopology(topology(replicas = listOf(relation(REGION)))),
                withTopology(topology(replicas = listOf(relation(OTHER, REGION)))),
                withTopology(
                    topology(
                        replicas = listOf(
                            relation(OTHER).toBuilder()
                                .arn(mrkArn(OTHER).replace(ACCOUNT, "999999999999")).build(),
                        ),
                    ),
                ),
                withTopology(
                    topology(
                        replicas = listOf(
                            relation(OTHER).toBuilder()
                                .arn(mrkArn(OTHER).replace(MRK, "mrk-${"b".repeat(32)}")).build(),
                        ),
                    ),
                ),
                withTopology(
                    topology(
                        replicas = listOf(
                            relation("cn-north-1").toBuilder()
                                .arn(mrkArn("cn-north-1").replace("arn:aws:", "arn:aws-cn:")).build(),
                        ),
                    ),
                ),
                multi(OTHER, "REPLICA").toBuilder().multiRegionConfiguration(topology(replicas = emptyList())).build(),
            )) {
                val region = if (broken.arn() == mrkArn(OTHER)) OTHER else REGION
                KmsObservations(mapOf(region to ObservationStub(region, broken)))
                    .describe(RootKey(mrkArn(region))).isLeft() shouldBe true
            }
        }

        test("alias resolution is observational and rejects reserved or malformed names before calls") {
            val client = ObservationStub()
            val observations = KmsObservations(mapOf(REGION to client))
            observations.resolveAlias(REGION, "alias/application/root").value().metadata.location.root shouldBe
                RootKey(ARN)
            client.calls.peek() shouldBe listOf("describe:alias/application/root")
            for (alias in listOf(
                "alias/aws/s3", "alias/", "root", ARN, "alias/root space", "alias/${"a".repeat(251)}",
            )) {
                observations.resolveAlias(REGION, alias).code() shouldBe KeyFailureCode.InvalidInput
            }
            client.calls.peek().size shouldBe 1
            val wrong = ObservationStub(metadata = single().toBuilder().arn(ARN.replace(REGION, OTHER)).build())
            KmsObservations(mapOf(REGION to wrong)).resolveAlias(REGION, "alias/root").code() shouldBe
                KeyFailureCode.Conflict
        }

        test("root listings read every page and verify each stable identity") {
            val client = ObservationStub(
                keys = mapOf(
                    null to keys(listOf(entry(ARN)), true, "next"),
                    "next" to keys(listOf(entry(ARN.replace(UUID, UUID2))), false),
                ),
            )
            val result = KmsObservations(mapOf(REGION to client)).listRoots(REGION).value()
            result.locations.map { it.root.id } shouldBe listOf(ARN, ARN.replace(UUID, UUID2))
            client.calls.peek() shouldBe listOf("keys:null", "keys:next")
            KmsObservations(mapOf(REGION to ObservationStub(keys = mapOf(null to keys(emptyList(), false)))))
                .listRoots(REGION).value().locations shouldBe emptyList()
            for (bad in listOf(
                entry(ARN).toBuilder().keyId(UUID2).build(), entry(ARN.replace(REGION, OTHER)),
                KeyListEntry.builder().keyId(UUID).build(),
            )) {
                KmsObservations(mapOf(REGION to ObservationStub(keys = mapOf(null to keys(listOf(bad), false)))))
                    .listRoots(REGION).isLeft() shouldBe true
            }
        }

        test("keys and tags reject every malformed pagination completion") {
            for ((truncated, marker) in listOf(
                null to null, true to null, true to "", false to "unexpected",
            )) {
                val client = ObservationStub(
                    keys = mapOf(null to keys(emptyList(), truncated, marker)),
                    tags = mapOf(null to tags(emptyList(), truncated, marker)),
                )
                val observations = KmsObservations(mapOf(REGION to client))
                observations.listRoots(REGION).code() shouldBe KeyFailureCode.IncompleteEvidence
                observations.tags(RootKey(ARN)).code() shouldBe KeyFailureCode.IncompleteEvidence
            }
            for (marker in listOf("a", "b")) {
                val client = ObservationStub(
                    keys = mapOf(
                        null to keys(emptyList(), true, "a"), "a" to keys(emptyList(), true, "b"),
                        "b" to keys(emptyList(), true, marker),
                    ),
                    tags = mapOf(
                        null to tags(emptyList(), true, "a"), "a" to tags(emptyList(), true, "b"),
                        "b" to tags(emptyList(), true, marker),
                    ),
                )
                val observations = KmsObservations(mapOf(REGION to client))
                observations.listRoots(REGION).code() shouldBe KeyFailureCode.IncompleteEvidence
                observations.tags(RootKey(ARN)).code() shouldBe KeyFailureCode.IncompleteEvidence
            }
            val absent = ObservationStub(
                keys = mapOf(null to ListKeysResponse.builder().truncated(false).build()),
                tags = mapOf(null to ListResourceTagsResponse.builder().truncated(false).build()),
            )
            KmsObservations(mapOf(REGION to absent)).listRoots(REGION).code() shouldBe KeyFailureCode.IncompleteEvidence
            KmsObservations(mapOf(REGION to absent)).tags(RootKey(ARN)).code() shouldBe
                KeyFailureCode.IncompleteEvidence
        }

        test("invalid provider markers never permit a completed listing") {
            for (marker in listOf("非法", "a".repeat(1025))) {
                val client = ObservationStub(
                    keys = mapOf(null to keys(emptyList(), true, marker), marker to keys(emptyList(), false)),
                    tags = mapOf(null to tags(emptyList(), true, marker), marker to tags(emptyList(), false)),
                )
                KmsObservations(mapOf(REGION to client)).listRoots(REGION).isLeft() shouldBe true
                KmsObservations(mapOf(REGION to client)).tags(RootKey(ARN)).isLeft() shouldBe true
            }
        }

        test("tags read every page and reject duplicate or missing entries") {
            val client = ObservationStub(
                tags = mapOf(
                    null to tags(listOf(tag("one", "1")), true, "next"),
                    "next" to tags(listOf(tag("two", "")), false),
                ),
            )
            KmsObservations(mapOf(REGION to client)).tags(RootKey(ARN)).value().entries shouldBe
                mapOf("one" to "1", "two" to "")
            client.calls.peek() shouldBe listOf("tags:$ARN:null", "tags:$ARN:next")
            for (bad in listOf(Tag.builder().tagValue("v").build(), Tag.builder().tagKey("k").build(), tag("", "v"))) {
                KmsObservations(mapOf(REGION to ObservationStub(tags = mapOf(null to tags(listOf(bad), false)))))
                    .tags(RootKey(ARN)).code() shouldBe KeyFailureCode.IncompleteEvidence
            }
            val duplicates = ObservationStub(
                keys = mapOf(null to keys(listOf(entry(ARN)), true, "next"), "next" to keys(listOf(entry(ARN)), false)),
                tags = mapOf(
                    null to tags(listOf(tag("k", "v")), true, "next"),
                    "next" to tags(listOf(tag("k", "v")), false),
                ),
            )
            KmsObservations(mapOf(REGION to duplicates)).listRoots(REGION).code() shouldBe KeyFailureCode.Conflict
            KmsObservations(mapOf(REGION to duplicates)).tags(RootKey(ARN)).code() shouldBe KeyFailureCode.Conflict
        }
    }
}

private const val REGION = "eu-west-2"
private const val OTHER = "eu-west-1"
private const val ACCOUNT = "123456789012"
private const val UUID = "12345678-1234-1234-1234-123456789abc"
private const val UUID2 = "12345678-1234-1234-1234-123456789abd"
private const val ARN = "arn:aws:kms:$REGION:$ACCOUNT:key/$UUID"
private val MRK = "mrk-${"a".repeat(32)}"
private fun mrkArn(region: String) = "arn:aws:kms:$region:$ACCOUNT:key/$MRK"
private fun single() = KeyMetadata.builder().arn(ARN).keyId(UUID).awsAccountId(ACCOUNT).keyState("Enabled")
    .multiRegion(false).build()
private fun relation(region: String, reported: String = region) =
    MultiRegionKey.builder().arn(mrkArn(region)).region(reported).build()
private fun withTopology(config: MultiRegionConfiguration?) =
    multi().toBuilder().multiRegionConfiguration(config).build()
private fun topology(
    primary: MultiRegionKey = relation(REGION),
    replicas: List<MultiRegionKey> = listOf(relation(OTHER)),
) =
    MultiRegionConfiguration.builder().primaryKey(primary).replicaKeys(replicas).multiRegionKeyType("PRIMARY").build()
private fun multi(region: String = REGION, role: String = "PRIMARY") = KeyMetadata.builder()
    .arn(mrkArn(region)).keyId(MRK).awsAccountId(ACCOUNT).keyState("Enabled").multiRegion(true)
    .multiRegionConfiguration(topology().toBuilder().multiRegionKeyType(role).build()).build()
private fun entry(arn: String) = KeyListEntry.builder().keyArn(arn).keyId(arn.substringAfter("key/")).build()
private fun keys(entries: List<KeyListEntry>, truncated: Boolean?, marker: String? = null) =
    ListKeysResponse.builder().keys(entries).truncated(truncated).nextMarker(marker).build()
private fun tag(key: String, value: String) = Tag.builder().tagKey(key).tagValue(value).build()
private fun tags(entries: List<Tag>, truncated: Boolean?, marker: String? = null) =
    ListResourceTagsResponse.builder().tags(entries).truncated(truncated).nextMarker(marker).build()
private fun <A> Either<KeyOperationFailure, A>.value(): A = (this as Either.Right).value
private fun <A> Either<KeyOperationFailure, A>.code(): KeyFailureCode = (this as Either.Left).value.code

private class ObservationStub(
    private val region: String = REGION,
    private val metadata: KeyMetadata? = single(),
    private val keys: Map<String?, ListKeysResponse> = emptyMap(),
    private val tags: Map<String?, ListResourceTagsResponse> = emptyMap(),
) : KmsClient {
    val calls = Shared(emptyList<String>())
    override fun serviceName() = "kms"
    override fun close() = Unit
    override fun serviceClientConfiguration(): KmsServiceClientConfiguration =
        KmsServiceClientConfiguration.builder().region(Region.of(region)).build()
    override fun describeKey(request: DescribeKeyRequest): DescribeKeyResponse {
        calls.update { it + "describe:${request.keyId()}" }
        return DescribeKeyResponse.builder().keyMetadata(metadata).build()
    }
    override fun listKeys(request: ListKeysRequest): ListKeysResponse {
        calls.update { it + "keys:${request.marker()}" }
        return keys.getValue(request.marker())
    }
    override fun listResourceTags(request: ListResourceTagsRequest): ListResourceTagsResponse {
        calls.update { it + "tags:${request.keyId()}:${request.marker()}" }
        return tags.getValue(request.marker())
    }
}
