package dev.surefold.tenancy.crypto

import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import java.time.Instant

object InMemoryKeyStoreSuite : Suite() {
    init {
        test("an absent root remains an unresolved observation without creating state") {
            val store = InMemoryKeyStore()
            for (operation in dataOperations) {
                store.lookup(storeLocation.root, operation) shouldBe rejected(operation, KeyFailureCode.NotObserved)
            }
            store.state.peek().roots shouldBe emptyMap()
        }

        test("initial mutable containers are captured before they can bypass Shared") {
            val root = storedRoot()
            val roots = mutableMapOf(root.location.root to root)
            val aliases = mutableMapOf((storeLocation.region to "alias/held") to root.location.root)
            val faults = mutableListOf(InMemoryFault(KeyOperation.Generate, KeyFailureCode.Unavailable))
            val store = InMemoryKeyStore(InMemoryKeyState(roots, aliases, faults))
            roots.clear()
            aliases.clear()
            faults.clear()
            store.state.peek().roots[root.location.root] shouldBe root
            store.state.peek().aliases.size shouldBe 1
            store.state.peek().faults.size shouldBe 1
        }

        test("regional metadata includes every replica including the current one") {
            val primary = storedRoot()
            val replicaLocation = storeLocation.copy(root = RootKey("memory/replica"), region = "eu-west-1")
            val replica = primary.copy(location = replicaLocation)
            val state = InMemoryKeyState(roots = mapOf(storeLocation.root to primary, replicaLocation.root to replica))
            val primaryMetadata = state.metadata(primary)
            val replicaMetadata = state.metadata(replica)
            primaryMetadata.primary shouldBe storeLocation
            replicaMetadata.primary shouldBe storeLocation
            primaryMetadata.replicas shouldBe listOf(replicaLocation)
            replicaMetadata.replicas shouldBe listOf(replicaLocation)
            replicaMetadata.location shouldBe replicaLocation
        }

        test("enabled root lookup supplies its held material to all data operations") {
            val root = storedRoot()
            val store = InMemoryKeyStore(InMemoryKeyState(roots = mapOf(root.location.root to root)))
            for (operation in dataOperations) {
                (store.lookup(root.location.root, operation) as Either.Right).value shouldBe root.key
            }
            val data = InMemoryDataKeyOperations(lookup = store::lookup)
            val context = EncryptionContext(mapOf("tenant_id" to "explicit"))
            val generated = (data.generateDataKey(root.location.root, context) as Either.Right).value
            val opened = (data.unwrap(root.location.root, generated.wrapped, context) as Either.Right).value
            opened.toList() shouldBe generated.plaintext.toList()
        }

        test("every retryable root state reports unavailability and every unusable root is refused") {
            for (state in RootState.entries.filter { it != RootState.Enabled }) {
                val root = storedRoot().copy(state = state)
                val store = InMemoryKeyStore(InMemoryKeyState(roots = mapOf(root.location.root to root)))
                val code = when (state) {
                    RootState.Creating, RootState.Updating, RootState.Unavailable, RootState.Unknown ->
                        KeyFailureCode.Unavailable

                    RootState.Disabled, RootState.PendingDeletion, RootState.PendingReplicaDeletion ->
                        KeyFailureCode.Refused

                    RootState.Enabled -> error("enabled is covered by the success story")
                }
                for (operation in dataOperations) {
                    store.lookup(root.location.root, operation) shouldBe rejected(operation, code)
                }
                root.state shouldBe state
            }
        }

        test("a before fault overrides lookup once and consumes exactly its matching occurrence") {
            val root = storedRoot()
            val unrelated = InMemoryFault(KeyOperation.Wrap, KeyFailureCode.IncompleteEvidence, RootKey("unrelated"))
            val after = InMemoryFault(KeyOperation.Wrap, KeyFailureCode.UncertainMutation, afterSuccess = true)
            val otherOperation = InMemoryFault(KeyOperation.Describe, KeyFailureCode.Unavailable)
            val matching = InMemoryFault(KeyOperation.Wrap, KeyFailureCode.Unavailable, root.location.root)
            val faults = listOf(unrelated, after, otherOperation, matching, matching)
            val initial = InMemoryKeyState(roots = mapOf(root.location.root to root), faults = faults)
            val store = InMemoryKeyStore(initial)
            store.lookup(root.location.root, KeyOperation.Wrap) shouldBe
                rejected(KeyOperation.Wrap, KeyFailureCode.Unavailable)
            store.state.peek().faults shouldBe listOf(unrelated, after, otherOperation, matching)
            store.lookup(root.location.root, KeyOperation.Wrap) shouldBe
                rejected(KeyOperation.Wrap, KeyFailureCode.Unavailable)
            store.state.peek().faults shouldBe listOf(unrelated, after, otherOperation)
            (store.lookup(root.location.root, KeyOperation.Wrap) as Either.Right).value shouldBe root.key
            initial.faults shouldBe faults
        }

        test("a wildcard before fault is consumed even when the requested root is not observed") {
            val fault = InMemoryFault(KeyOperation.Unwrap, KeyFailureCode.Unavailable)
            val store = InMemoryKeyStore(InMemoryKeyState(faults = listOf(fault)))
            store.lookup(storeLocation.root, KeyOperation.Unwrap) shouldBe
                rejected(KeyOperation.Unwrap, KeyFailureCode.Unavailable)
            store.lookup(storeLocation.root, KeyOperation.Unwrap) shouldBe
                rejected(KeyOperation.Unwrap, KeyFailureCode.NotObserved)
            store.state.peek().faults shouldBe emptyList()
        }

        test("immutable state transitions retain aliases ownership evidence and prior deletion observations") {
            val root = storedRoot()
            val initial = InMemoryKeyState(roots = mapOf(root.location.root to root))
            val store = InMemoryKeyStore(initial)
            val scheduledAt = Instant.parse("2026-11-28T00:00:00Z")
            val pending = root.copy(state = RootState.PendingDeletion, rotation = true, deletionAt = scheduledAt)
            val alias = storeLocation.region to "alias/held"
            store.state.update {
                it.copy(
                    roots = it.roots + (root.location.root to pending),
                    aliases = it.aliases + (alias to root.location.root),
                )
            }
            val observed = store.state.peek()
            observed.roots.getValue(root.location.root).deletionAt shouldBe scheduledAt
            observed.roots.getValue(root.location.root).rotation shouldBe true
            observed.roots.getValue(root.location.root).creation.tags shouldBe root.creation.tags
            observed.aliases[alias] shouldBe root.location.root
            initial.aliases shouldBe emptyMap()
            initial.roots.getValue(root.location.root).state shouldBe RootState.Enabled
            root.toString().contains("sensitive-policy") shouldBe false
            root.toString().contains("sensitive-description") shouldBe false
            root.toString().contains("sensitive-tag") shouldBe false
        }
    }
}

private val dataOperations = listOf(KeyOperation.Generate, KeyOperation.Wrap, KeyOperation.Unwrap)
private val storeLocation = RootLocation(RootKey("memory/primary"), "123456789012", "aws", "eu-west-2")

private fun storedRoot() = InMemoryRoot(
    storeLocation,
    storeLocation.root,
    RootCreation(storeLocation.region, "sensitive-policy", "sensitive-description", mapOf("sensitive-tag" to "value")),
    InMemoryWrappingKey(),
)

private fun rejected(operation: KeyOperation, code: KeyFailureCode) = Either.Left(KeyOperationFailure(operation, code))
