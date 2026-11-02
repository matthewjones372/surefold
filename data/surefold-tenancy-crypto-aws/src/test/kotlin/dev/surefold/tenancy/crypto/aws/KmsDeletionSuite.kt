package dev.surefold.tenancy.crypto.aws

import dev.surefold.Shared
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import dev.surefold.tenancy.crypto.DeletionState
import dev.surefold.tenancy.crypto.ExpectedKeySet
import dev.surefold.tenancy.crypto.KeyFailureCode
import dev.surefold.tenancy.crypto.KeyOperation
import dev.surefold.tenancy.crypto.KeyOperationFailure
import dev.surefold.tenancy.crypto.RootExpectation
import dev.surefold.tenancy.crypto.RootKey
import dev.surefold.tenancy.crypto.RootLocation
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.KmsServiceClientConfiguration
import software.amazon.awssdk.services.kms.model.DescribeKeyRequest
import software.amazon.awssdk.services.kms.model.DescribeKeyResponse
import software.amazon.awssdk.services.kms.model.KeyMetadata
import software.amazon.awssdk.services.kms.model.KmsInvalidStateException
import software.amazon.awssdk.services.kms.model.ListResourceTagsRequest
import software.amazon.awssdk.services.kms.model.ListResourceTagsResponse
import software.amazon.awssdk.services.kms.model.MultiRegionConfiguration
import software.amazon.awssdk.services.kms.model.MultiRegionKey
import software.amazon.awssdk.services.kms.model.NotFoundException
import software.amazon.awssdk.services.kms.model.ScheduleKeyDeletionRequest
import software.amazon.awssdk.services.kms.model.ScheduleKeyDeletionResponse
import software.amazon.awssdk.services.kms.model.Tag
import java.time.Instant
import java.util.concurrent.CancellationException

object KmsDeletionSuite : Suite() {
    init {
        test("complete tags authorize replica first scheduling through each explicit regional client") {
            val world = DeletionWorld()
            val result = world.schedule()
            result.failure shouldBe null
            result.regions.map { it.location } shouldBe listOf(location(REPLICA_REGION), location(PRIMARY_REGION))
            result.regions.map { it.state } shouldBe
                listOf(DeletionState.PendingDeletion, DeletionState.PendingReplicaDeletion)
            result.regions.map { it.effectiveAt } shouldBe listOf(DELETION_DATE, null)
            world.mutations() shouldBe listOf(REPLICA_REGION, PRIMARY_REGION)
            for (client in listOf(world.primary, world.replica)) {
                client.calls.peek().filterIsInstance<ScheduleKeyDeletionRequest>().single().let {
                    it.keyId() shouldBe location(client.region).root.id
                    it.pendingWindowInDays() shouldBe 9
                }
                client.calls.peek().filterIsInstance<ListResourceTagsRequest>().map { it.marker() } shouldBe
                    listOf(null, "second")
            }
        }
        test("waiting days outside seven through thirty fail before observation or mutation") {
            for (days in listOf(-1, 0, 6, 31, Int.MAX_VALUE)) {
                val world = DeletionWorld()
                world.schedule(days = days).failure shouldBe failure(KeyFailureCode.InvalidInput)
                world.events.peek() shouldBe emptyList()
            }
        }
        test("every supplied location field must match its ARN before any provider call") {
            for (bad in listOf(
                location(REPLICA_REGION).copy(account = "999999999999"),
                location(REPLICA_REGION).copy(partition = "aws-cn"),
                location(REPLICA_REGION).copy(region = "us-east-1"),
                location(REPLICA_REGION).copy(root = RootKey("alias/root")),
            )) {
                val world = DeletionWorld()
                world.schedule(expected = expected(replicas = listOf(expectation(bad)))).failure?.operation shouldBe
                    KeyOperation.ScheduleDeletion
                world.events.peek() shouldBe emptyList()
            }
        }
        test("duplicate roots or regions and foreign account partition or MRK never authorize deletion") {
            val replica = location(REPLICA_REGION)
            val otherId = "mrk-${"b".repeat(32)}"
            for (members in listOf(
                listOf(expectation(replica), expectation(replica)),
                listOf(expectation(location(PRIMARY_REGION))),
                listOf(
                    expectation(
                        replica.copy(
                            root = RootKey(replica.root.id.replace(ACCOUNT, "999999999999")),
                            account = "999999999999",
                        ),
                    ),
                ),
                listOf(expectation(replica.copy(root = RootKey(replica.root.id.replace(MRK, otherId))))),
                listOf(
                    expectation(
                        RootLocation(
                            RootKey("arn:aws-cn:kms:cn-north-1:$ACCOUNT:key/$MRK"),
                            ACCOUNT, "aws-cn", "cn-north-1",
                        ),
                    ),
                ),
            )) {
                val world = DeletionWorld()
                world.schedule(expected = expected(replicas = members)).failure shouldBe
                    failure(KeyFailureCode.Conflict)
                world.mutations() shouldBe emptyList()
            }
        }
        test("a mismatch in any member metadata or tags prevents every destructive call") {
            for (region in listOf(PRIMARY_REGION, REPLICA_REGION)) {
                for (bad in listOf(
                    metadata(region).toBuilder().awsAccountId("999999999999").build(),
                    metadata(region).toBuilder()
                        .multiRegionConfiguration(topology(region, replicas = emptyList())).build(),
                    metadata(region).toBuilder()
                        .multiRegionConfiguration(topology(region, primary = REPLICA_REGION)).build(),
                    metadata(region).toBuilder().multiRegionConfiguration(
                        topology(
                            region,
                            replicas = listOf(REPLICA_REGION, "us-east-1"),
                        ),
                    ).build(),
                )) {
                    val world = DeletionWorld(describe = { if (it == region) bad else metadata(it) })
                    world.schedule().failure shouldBe failure(KeyFailureCode.Conflict)
                    world.mutations() shouldBe emptyList()
                }
                val world = DeletionWorld(tags = { current, marker ->
                    if (current == region) tagsPage(mapOf("owner" to "wrong")) else completeTags(marker)
                })
                world.schedule().failure shouldBe failure(KeyFailureCode.Conflict)
                world.mutations() shouldBe emptyList()
            }
        }
        test("omitted unexpected and incomplete replica sets cannot authorize deletion") {
            val world = DeletionWorld()
            world.schedule(expected = expected(replicas = emptyList())).failure shouldBe
                failure(KeyFailureCode.Conflict)
            world.mutations() shouldBe emptyList()
            for (raw in listOf<KeyMetadata?>(
                null,
                metadata(REPLICA_REGION).toBuilder()
                    .multiRegionConfiguration(null as MultiRegionConfiguration?).build(),
            )) {
                val incomplete = DeletionWorld(describe = { if (it == REPLICA_REGION) raw else metadata(it) })
                incomplete.schedule().failure shouldBe failure(KeyFailureCode.IncompleteEvidence)
                incomplete.mutations() shouldBe emptyList()
            }
        }
        test("observation failures are normalized and missing metadata never proves destruction") {
            for ((read, code) in listOf<Pair<() -> KeyMetadata?, KeyFailureCode>>(
                { throw NotFoundException.builder().build() } to KeyFailureCode.NotObserved,
                { throw SdkClientException.create("private") } to KeyFailureCode.Unavailable,
                { null } to KeyFailureCode.IncompleteEvidence,
            )) {
                val world = DeletionWorld(describe = { if (it == REPLICA_REGION) read() else metadata(it) })
                val result = world.schedule()
                result.failure shouldBe failure(code)
                result.regions shouldBe emptyList()
                world.mutations() shouldBe emptyList()
            }
        }
        test("malformed later tag pages and unavailable regional clients prevent all mutations") {
            val world = DeletionWorld(tags = { region, marker ->
                if (region == REPLICA_REGION && marker != null) tagsPage(emptyMap()).toBuilder()
                    .truncated(true).build() else completeTags(marker)
            })
            world.schedule().failure shouldBe failure(KeyFailureCode.IncompleteEvidence)
            world.mutations() shouldBe emptyList()
            val missing = KmsRootDeletion(KmsObservations(mapOf(PRIMARY_REGION to world.primary)))
                .scheduleDeletion(expected(), 9)
            missing.failure shouldBe failure(KeyFailureCode.Unavailable)
            world.mutations() shouldBe emptyList()
        }
        test("transitional unavailable and unknown states are retryable before all mutations") {
            for (region in listOf(PRIMARY_REGION, REPLICA_REGION)) {
                for (state in listOf("Creating", "Updating", "Unavailable", "future")) {
                    val world = DeletionWorld(describe = { metadata(it, if (it == region) state else "Enabled") })
                    world.schedule().failure shouldBe failure(KeyFailureCode.Unavailable)
                    world.mutations() shouldBe emptyList()
                }
            }
        }
        test("ordinary single region roots and empty required tag subsets use explicit authority") {
            for (state in listOf("Enabled", "Disabled")) {
                val root = location(PRIMARY_REGION, SINGLE_ID)
                val events = Shared(emptyList<String>())
                val client = DeletionStub(
                    PRIMARY_REGION, events, { metadata(PRIMARY_REGION, state, single = true) },
                    { tagsPage(mapOf("extra" to "retained")) },
                    { deletionReply(PRIMARY_REGION, "PendingDeletion", SINGLE_ID) },
                )
                val result = KmsRootDeletion(KmsObservations(mapOf(PRIMARY_REGION to client)))
                    .scheduleDeletion(ExpectedKeySet(expectation(root, emptyMap()), emptyList()), 30)
                result.failure shouldBe null
                result.regions.single().effectiveAt shouldBe DELETION_DATE
                events.peek().last() shouldBe "schedule:$PRIMARY_REGION"
            }
        }
        test("pending regional states preserve provider dates without resetting waiting windows") {
            val world = DeletionWorld(describe = {
                metadata(it, if (it == PRIMARY_REGION) "PendingReplicaDeletion" else "PendingDeletion")
                    .toBuilder().deletionDate(DELETION_DATE).build()
            })
            for (days in listOf(7, 30)) {
                val result = world.schedule(days = days)
                result.failure shouldBe null
                result.regions.map { it.effectiveAt } shouldBe listOf(DELETION_DATE, null)
            }
            world.mutations() shouldBe emptyList()
        }
        test("missing optional pending deletion dates remain unknown without rescheduling") {
            val world = DeletionWorld(describe = { metadata(it, "PendingDeletion") })
            val result = world.schedule()
            result.failure shouldBe null
            result.regions.map { it.effectiveAt } shouldBe listOf(null, null)
            world.mutations() shouldBe emptyList()
        }
        test("a successful replica survives a later primary refusal or lost reply") {
            for ((mutate, code) in listOf<Pair<() -> ScheduleKeyDeletionResponse, KeyFailureCode>>(
                { throw KmsInvalidStateException.builder().build() } to KeyFailureCode.Refused,
                { throw SdkClientException.create("private") } to KeyFailureCode.UncertainMutation,
            )) {
                val world = DeletionWorld(mutate = { region ->
                    if (region == PRIMARY_REGION) mutate() else deletionReply(region)
                })
                val result = world.schedule()
                result.failure shouldBe failure(code)
                result.regions.map { it.location } shouldBe listOf(location(REPLICA_REGION))
                result.regions.single().effectiveAt shouldBe DELETION_DATE
                world.mutations() shouldBe listOf(REPLICA_REGION, PRIMARY_REGION)
            }
        }
        test("a malformed mutation ARN or state is uncertain and preserves earlier progress") {
            for (reply in listOf(
                ScheduleKeyDeletionResponse.builder().build(),
                deletionReply(REPLICA_REGION),
                deletionReply(PRIMARY_REGION, "Enabled"),
                deletionReply(PRIMARY_REGION).toBuilder().keyId("alias/root").build(),
            )) {
                val world = DeletionWorld(mutate = { if (it == PRIMARY_REGION) reply else deletionReply(it) })
                val result = world.schedule()
                result.failure shouldBe failure(KeyFailureCode.UncertainMutation)
                result.regions.map { it.location } shouldBe listOf(location(REPLICA_REGION))
            }
        }
        test("state races accept only freshly verified pending states and provider dates") {
            for (region in listOf(PRIMARY_REGION, REPLICA_REGION)) {
                val reads = Shared(emptyMap<String, Int>())
                val world = DeletionWorld(describe = { current ->
                    reads.update { it + (current to ((it[current] ?: 0) + 1)) }
                    if (current == region && reads.peek().getValue(current) > 1) metadata(
                        current,
                        if (current == PRIMARY_REGION) "PendingReplicaDeletion" else "PendingDeletion",
                    )
                        .toBuilder().deletionDate(DELETION_DATE).build() else metadata(current)
                }, mutate = { current ->
                    if (current == region) throw KmsInvalidStateException.builder().build()
                    deletionReply(current)
                })
                val result = world.schedule()
                result.failure shouldBe null
                result.regions.map { it.effectiveAt } shouldBe listOf(DELETION_DATE, null)
                reads.peek().getValue(region) shouldBe 2
            }
        }
        test("state race refusal or missing identity never becomes deletion evidence") {
            for ((readback, code) in listOf<Pair<() -> KeyMetadata?, KeyFailureCode>>(
                { metadata(PRIMARY_REGION) } to KeyFailureCode.Refused,
                { throw NotFoundException.builder().build() } to KeyFailureCode.NotObserved,
                { null } to KeyFailureCode.IncompleteEvidence,
                {
                    metadata(PRIMARY_REGION, "PendingDeletion").toBuilder()
                        .arn(location(REPLICA_REGION).root.id).build()
                }
                    to KeyFailureCode.Conflict,
            )) {
                val reads = Shared(0)
                val world = DeletionWorld(describe = { region ->
                    if (region == PRIMARY_REGION) {
                        reads.update { it + 1 }
                        if (reads.peek() > 1) readback() else metadata(region)
                    } else metadata(region)
                }, mutate = { region ->
                    if (region == PRIMARY_REGION) throw KmsInvalidStateException.builder().build()
                    deletionReply(region)
                })
                val result = world.schedule()
                result.failure shouldBe failure(code)
                result.regions.map { it.location } shouldBe listOf(location(REPLICA_REGION))
            }
        }
        test("cancellation and fatal causes propagate before state race readback") {
            for (cause in listOf(CancellationException("cancel"), LinkageError("fatal"))) {
                val world = DeletionWorld(mutate = { throw KmsInvalidStateException.builder().cause(cause).build() })
                if (cause is CancellationException) {
                    shouldThrow<CancellationException>({ world.schedule() }) { it shouldBe cause }
                } else shouldThrow<LinkageError>({ world.schedule() }) { it shouldBe cause }
                world.replica.calls.peek().filterIsInstance<DescribeKeyRequest>().size shouldBe 1
            }
        }
        test("successful calls followed by retries retain original provider windows without control mutations") {
            val pending = Shared(emptySet<String>())
            val world = DeletionWorld(describe = { region ->
                if (region in pending.peek()) metadata(
                    region,
                    if (region == PRIMARY_REGION) "PendingReplicaDeletion" else "PendingDeletion",
                )
                    .toBuilder().deletionDate(DELETION_DATE).build() else metadata(region)
            }, mutate = { region ->
                pending.update { it + region }
                deletionReply(region)
            })
            val first = world.schedule(days = 7)
            val again = world.schedule(days = 30)
            again.failure shouldBe null
            again.regions shouldBe first.regions
            world.mutations() shouldBe listOf(REPLICA_REGION, PRIMARY_REGION)
            world.events.peek().filterNot {
                it.startsWith("describe:") || it.startsWith("tags:") || it.startsWith("schedule:")
            } shouldBe emptyList()
            val firstMutation = world.events.peek().indexOfFirst { it.startsWith("schedule:") }
            world.events.peek().take(firstMutation).count { it.startsWith("tags:") } shouldBe 4
        }
        test("a verified MRK primary can remain pending replicas with no known date or current replica") {
            val events = Shared(emptyList<String>())
            val client = DeletionStub(PRIMARY_REGION, events, {
                metadata(PRIMARY_REGION, "PendingReplicaDeletion").toBuilder()
                    .multiRegionConfiguration(topology(PRIMARY_REGION, replicas = emptyList())).build()
            }, { completeTags(it) }, { throw AssertionError("pending primary was rescheduled") })
            val result = KmsRootDeletion(KmsObservations(mapOf(PRIMARY_REGION to client)))
                .scheduleDeletion(expected(replicas = emptyList()), 7)
            result.failure shouldBe null
            result.regions.single().state shouldBe DeletionState.PendingReplicaDeletion
            result.regions.single().effectiveAt shouldBe null
        }
        test("a replica cannot claim pending replica deletion in observations or mutation replies") {
            val observed = DeletionWorld(describe = {
                metadata(it, if (it == REPLICA_REGION) "PendingReplicaDeletion" else "Enabled")
            })
            observed.schedule().failure shouldBe failure(KeyFailureCode.Conflict)
            observed.mutations() shouldBe emptyList()
            val mutated = DeletionWorld(mutate = { deletionReply(it, "PendingReplicaDeletion") })
            val result = mutated.schedule()
            result.failure shouldBe failure(KeyFailureCode.UncertainMutation)
            result.regions shouldBe emptyList()
            mutated.mutations() shouldBe listOf(REPLICA_REGION)
        }
        test("state race readbacks retain optional unknown dates without further scheduling") {
            val reads = Shared(0)
            val world = DeletionWorld(describe = { region ->
                if (region == REPLICA_REGION) {
                    reads.update { it + 1 }
                    metadata(region, if (reads.peek() > 1) "PendingDeletion" else "Enabled")
                } else metadata(region)
            }, mutate = { region ->
                if (region == REPLICA_REGION) throw KmsInvalidStateException.builder().build()
                deletionReply(region)
            })
            val result = world.schedule()
            result.failure shouldBe null
            result.regions.map { it.effectiveAt } shouldBe listOf(null, null)
            world.mutations() shouldBe listOf(REPLICA_REGION, PRIMARY_REGION)
        }
        test("state race topology changes cannot authorize primary acceptance") {
            val reads = Shared(0)
            val world = DeletionWorld(describe = { region ->
                if (region == PRIMARY_REGION) {
                    reads.update { it + 1 }
                    if (reads.peek() > 1) metadata(region, "PendingReplicaDeletion").toBuilder()
                        .multiRegionConfiguration(topology(region, replicas = listOf(REPLICA_REGION, "us-east-1")))
                        .build() else metadata(region)
                } else metadata(region)
            }, mutate = { region ->
                if (region == PRIMARY_REGION) throw KmsInvalidStateException.builder().build()
                deletionReply(region)
            })
            val result = world.schedule()
            result.failure shouldBe failure(KeyFailureCode.Conflict)
            result.regions.map { it.location } shouldBe listOf(location(REPLICA_REGION))
        }
        test("unexpected programming errors and direct cancellation propagate") {
            for (cause in listOf(CancellationException("cancel"), IllegalStateException("bug"))) {
                val world = DeletionWorld(mutate = { throw cause })
                if (cause is CancellationException) {
                    shouldThrow<CancellationException>({ world.schedule() }) { it shouldBe cause }
                } else shouldThrow<IllegalStateException>({ world.schedule() }) { it shouldBe cause }
            }
        }
    }
}

private const val PRIMARY_REGION = "eu-west-2"
private const val REPLICA_REGION = "eu-west-1"
private const val ACCOUNT = "123456789012"
private val MRK = "mrk-${"a".repeat(32)}"
private const val SINGLE_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
private val DELETION_DATE = Instant.parse("2026-11-28T12:34:56Z")
private fun location(region: String, id: String = MRK) =
    RootLocation(RootKey("arn:aws:kms:$region:$ACCOUNT:key/$id"), ACCOUNT, "aws", region)
private fun expectation(location: RootLocation, tags: Map<String, String> = mapOf("owner" to "opaque")) =
    RootExpectation(location, tags)
private fun expected(replicas: List<RootExpectation> = listOf(expectation(location(REPLICA_REGION)))) =
    ExpectedKeySet(expectation(location(PRIMARY_REGION)), replicas)
private fun failure(code: KeyFailureCode) = KeyOperationFailure(KeyOperation.ScheduleDeletion, code)
private fun topology(
    region: String,
    primary: String = PRIMARY_REGION,
    replicas: List<String> = listOf(REPLICA_REGION),
) =
    MultiRegionConfiguration.builder().multiRegionKeyType(if (region == PRIMARY_REGION) "PRIMARY" else "REPLICA")
        .primaryKey(related(primary)).replicaKeys(replicas.map { related(it) }).build()
private fun related(region: String) = MultiRegionKey.builder().arn(location(region).root.id).region(region).build()
private fun metadata(region: String, state: String = "Enabled", single: Boolean = false): KeyMetadata =
    KeyMetadata.builder().arn(location(region, if (single) SINGLE_ID else MRK).root.id)
        .keyId(if (single) SINGLE_ID else MRK).awsAccountId(ACCOUNT).multiRegion(!single).keyState(state)
        .multiRegionConfiguration(if (single) null else topology(region)).build()
private fun deletionReply(
    region: String,
    state: String =
        if (region == PRIMARY_REGION) "PendingReplicaDeletion" else "PendingDeletion",
    id: String = MRK,
) =
    ScheduleKeyDeletionResponse.builder().keyId(location(region, id).root.id).keyState(state)
        .deletionDate(if (state == "PendingReplicaDeletion") null else DELETION_DATE).build()
private fun tagsPage(entries: Map<String, String>) = ListResourceTagsResponse.builder()
    .tags(entries.map { (key, value) -> Tag.builder().tagKey(key).tagValue(value).build() }).truncated(false).build()
private fun completeTags(marker: String?) = if (marker == null) tagsPage(mapOf("extra" to "retained")).toBuilder()
    .truncated(true).nextMarker("second").build() else tagsPage(mapOf("owner" to "opaque"))

private class DeletionWorld(
    describe: (String) -> KeyMetadata? = { metadata(it) },
    tags: (String, String?) -> ListResourceTagsResponse = { _, marker -> completeTags(marker) },
    mutate: (String) -> ScheduleKeyDeletionResponse = { deletionReply(it) },
) {
    val events = Shared(emptyList<String>())
    val primary = DeletionStub(
        PRIMARY_REGION, events, { describe(PRIMARY_REGION) },
        { tags(PRIMARY_REGION, it) }, { mutate(PRIMARY_REGION) },
    )
    val replica = DeletionStub(
        REPLICA_REGION, events, { describe(REPLICA_REGION) },
        { tags(REPLICA_REGION, it) }, { mutate(REPLICA_REGION) },
    )
    private val subject = KmsRootDeletion(KmsObservations(mapOf(PRIMARY_REGION to primary, REPLICA_REGION to replica)))
    fun schedule(expected: ExpectedKeySet = expected(), days: Int = 9) = subject.scheduleDeletion(expected, days)
    fun mutations() = events.peek().filter { it.startsWith("schedule:") }.map { it.removePrefix("schedule:") }
}

private class DeletionStub(
    val region: String,
    private val events: Shared<List<String>>,
    private val describe: () -> KeyMetadata?,
    private val tags: (String?) -> ListResourceTagsResponse,
    private val mutate: () -> ScheduleKeyDeletionResponse,
) : KmsClient {
    val calls = Shared(emptyList<Any>())
    override fun serviceName() = "kms"
    override fun close() = Unit
    override fun serviceClientConfiguration(): KmsServiceClientConfiguration =
        KmsServiceClientConfiguration.builder().region(Region.of(region)).build()
    override fun describeKey(request: DescribeKeyRequest): DescribeKeyResponse {
        calls.update { it + request }
        events.update { it + "describe:$region" }
        return DescribeKeyResponse.builder().keyMetadata(describe()).build()
    }
    override fun listResourceTags(request: ListResourceTagsRequest): ListResourceTagsResponse {
        calls.update { it + request }
        events.update { it + "tags:$region" }
        return tags(request.marker())
    }
    override fun scheduleKeyDeletion(request: ScheduleKeyDeletionRequest): ScheduleKeyDeletionResponse {
        calls.update { it + request }
        events.update { it + "schedule:$region" }
        return mutate()
    }
}
