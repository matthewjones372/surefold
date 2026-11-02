package io.github.matthewjones372.surefold.tenancy.crypto

import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import java.time.Instant

object KeyOperationsSuite : Suite() {
    init {
        test("an explicit context captures exactly the supplied entries and redacts them") {
            val supplied = mutableMapOf("tenant_id" to "platform", "purpose" to "enc", "version" to "1")
            val context = EncryptionContext(supplied)
            supplied["tenant_id"] = "changed"
            context.entries shouldBe mapOf("tenant_id" to "platform", "purpose" to "enc", "version" to "1")
            context.toString() shouldBe "EncryptionContext(redacted)"
            shouldThrow<UnsupportedOperationException> { (context.entries as MutableMap)["extra"] = "value" }
        }

        test("context boundaries and empty entries are preserved without tenant defaults") {
            val exact = mapOf("" to "", "a=b" to "c", "a" to "b=c", "tenant_id" to "platform")
            EncryptionContext(exact).entries shouldBe exact
            EncryptionContext(emptyMap()).entries shouldBe emptyMap()
        }

        test("creation and ownership evidence snapshot every tag and redact supplied strings") {
            val supplied = mutableMapOf("secret-tag-name" to "secret-tag-value", "unrelated" to "kept")
            val request = RootCreation("secret-region", "secret-policy", "secret-description", supplied)
            val tags = KeyTags(supplied)
            val expected = RootExpectation(primaryLocation, supplied)
            supplied.clear()
            request.tags shouldBe mapOf("secret-tag-name" to "secret-tag-value", "unrelated" to "kept")
            tags.entries shouldBe request.tags
            expected.requiredTags shouldBe request.tags
            request.policy shouldBe "secret-policy"
            request.description shouldBe "secret-description"
            request.toString() shouldBe "RootCreation(redacted)"
            tags.toString() shouldBe "KeyTags(redacted)"
            expected.toString() shouldBe "RootExpectation(redacted)"
            shouldThrow<UnsupportedOperationException> { (request.tags as MutableMap).clear() }
            shouldThrow<UnsupportedOperationException> { (tags.entries as MutableMap).clear() }
            shouldThrow<UnsupportedOperationException> { (expected.requiredTags as MutableMap).clear() }
        }

        test("metadata and listing capture regional relationships without retaining mutable lists") {
            val replicas = mutableListOf(replicaLocation)
            val metadata = RootMetadata(primaryLocation, RootState.Enabled, primaryLocation, replicas)
            val listing = RootListing(replicas)
            replicas.clear()
            metadata.location shouldBe primaryLocation
            metadata.primary shouldBe primaryLocation
            metadata.replicas shouldBe listOf(replicaLocation)
            listing.locations shouldBe listOf(replicaLocation)
            metadata.toString() shouldBe "RootMetadata(state=Enabled)"
            listing.toString() shouldBe "RootListing(observed=1)"
            shouldThrow<UnsupportedOperationException> { (metadata.replicas as MutableList).clear() }
            shouldThrow<UnsupportedOperationException> { (listing.locations as MutableList).clear() }
        }

        test("expected regional sets preserve duplicates for the interpreter to reject") {
            val primary = RootExpectation(primaryLocation, mapOf("owner" to "secret"))
            val replica = RootExpectation(replicaLocation, mapOf("owner" to "secret"))
            val supplied = mutableListOf(replica, replica)
            val expected = ExpectedKeySet(primary, supplied)
            supplied.clear()
            expected.primary shouldBe primary
            expected.replicas shouldBe listOf(replica, replica)
            expected.toString() shouldBe "ExpectedKeySet(redacted)"
            shouldThrow<UnsupportedOperationException> { (expected.replicas as MutableList).clear() }
        }

        test("deletion progress retains accepted regional dates alongside a stopping failure") {
            val date = Instant.parse("2026-11-28T12:00:00Z")
            val accepted = RegionalDeletion(replicaLocation, DeletionState.PendingDeletion, date)
            val supplied = mutableListOf(accepted)
            val failure = KeyOperationFailure(KeyOperation.ScheduleDeletion, KeyFailureCode.Unavailable)
            val progress = DeletionProgress(supplied, failure)
            supplied.clear()
            progress.regions shouldBe listOf(accepted)
            progress.regions.single().effectiveAt shouldBe date
            progress.failure shouldBe failure
            shouldThrow<UnsupportedOperationException> { (progress.regions as MutableList).clear() }
        }

        test("a primary waiting on replicas can have no effective deletion date") {
            val pending = RegionalDeletion(primaryLocation, DeletionState.PendingReplicaDeletion, null)
            val progress = DeletionProgress(listOf(pending), null)
            progress.regions.single().effectiveAt shouldBe null
            progress.failure shouldBe null
        }

        test("failure diagnostics contain only closed operation and code values") {
            for (operation in KeyOperation.entries) {
                for (code in KeyFailureCode.entries) {
                    KeyOperationFailure(operation, code).toString() shouldBe
                        "KeyOperationFailure(operation=$operation, code=$code)"
                }
            }
        }

        test("an independent implementation exposes every operation through the additive port") {
            val port: KeyOperations = RefusingOperations()
            val data: DataKeyOperations = port
            val roots: RootOperations = port
            val context = EncryptionContext(mapOf("tenant_id" to "platform"))
            val request = RootCreation(primaryLocation.region, "policy", "description", emptyMap())
            val expected = ExpectedKeySet(RootExpectation(primaryLocation, emptyMap()), emptyList())
            val replies = listOf(
                data.generateDataKey(primaryLocation.root, context),
                data.wrap(primaryLocation.root, byteArrayOf(1), context),
                data.unwrap(primaryLocation.root, byteArrayOf(2), context),
                roots.describe(primaryLocation.root),
                roots.listRoots(primaryLocation.region),
                roots.resolveAlias(primaryLocation.region, "alias/root"),
                roots.tags(primaryLocation.root),
                roots.createPrimary(request),
                roots.bindAlias(primaryLocation.root, "alias/root"),
                roots.enableRotation(primaryLocation.root),
                roots.createReplica(primaryLocation.root, request),
                roots.inspectReplica(primaryLocation.root, replicaLocation.root),
            )
            replies.map { (it as Either.Left).value.operation }.toSet() shouldBe
                KeyOperation.entries.toSet() - KeyOperation.ScheduleDeletion
            roots.scheduleDeletion(expected, 30).failure shouldBe
                KeyOperationFailure(KeyOperation.ScheduleDeletion, KeyFailureCode.Refused)
        }
    }
}

private val primaryLocation = RootLocation(RootKey("root-primary"), "123456789012", "aws", "eu-west-2")
private val replicaLocation = RootLocation(RootKey("root-replica"), "123456789012", "aws", "eu-west-1")

private class RefusingOperations : KeyOperations {
    private fun refused(operation: KeyOperation) = Either.Left(KeyOperationFailure(operation, KeyFailureCode.Refused))

    override fun generateDataKey(root: RootKey, context: EncryptionContext) = refused(KeyOperation.Generate)

    override fun wrap(root: RootKey, plaintext: ByteArray, context: EncryptionContext) = refused(KeyOperation.Wrap)

    override fun unwrap(root: RootKey, wrapped: ByteArray, context: EncryptionContext) = refused(KeyOperation.Unwrap)

    override fun describe(root: RootKey) = refused(KeyOperation.Describe)

    override fun listRoots(region: String) = refused(KeyOperation.ListRoots)

    override fun resolveAlias(region: String, alias: String) = refused(KeyOperation.ResolveAlias)

    override fun tags(root: RootKey) = refused(KeyOperation.Tags)

    override fun createPrimary(request: RootCreation) = refused(KeyOperation.CreatePrimary)

    override fun bindAlias(root: RootKey, alias: String) = refused(KeyOperation.BindAlias)

    override fun enableRotation(root: RootKey) = refused(KeyOperation.EnableRotation)

    override fun createReplica(primary: RootKey, request: RootCreation) = refused(KeyOperation.CreateReplica)

    override fun inspectReplica(primary: RootKey, replica: RootKey) = refused(KeyOperation.InspectReplica)

    override fun scheduleDeletion(expected: ExpectedKeySet, waitingDays: Int) =
        DeletionProgress(emptyList(), KeyOperationFailure(KeyOperation.ScheduleDeletion, KeyFailureCode.Refused))
}
