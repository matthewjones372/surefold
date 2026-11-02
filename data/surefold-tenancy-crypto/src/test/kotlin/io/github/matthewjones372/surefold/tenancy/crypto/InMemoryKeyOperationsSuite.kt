package io.github.matthewjones372.surefold.tenancy.crypto

import dev.surefold.TestClock
import dev.surefold.fail.Either
import dev.surefold.fail.map
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import java.time.Instant
import kotlin.time.Duration.Companion.days

object InMemoryKeyOperationsSuite : Suite() {
    init {
        test("discovery observes nothing without creating a root") {
            val memory = memory()
            memory.describe(RootKey("missing")) shouldBe rejected(KeyOperation.Describe, KeyFailureCode.NotObserved)
            memory.resolveAlias("eu-west-2", "alias/missing") shouldBe
                rejected(KeyOperation.ResolveAlias, KeyFailureCode.NotObserved)
            memory.listRoots("eu-west-2").value().locations shouldBe emptyList()
        }

        test("primary creation records explicit policy tags ownership and no implicit alias") {
            val memory = memory()
            val request = creation()
            val root = memory.createPrimary(request).value()
            root.location.account shouldBe "account"
            root.location.partition shouldBe "aws"
            root.primary shouldBe root.location
            root.state shouldBe RootState.Enabled
            memory.creation(root.location.root) shouldBe request
            memory.tags(root.location.root).value().entries shouldBe request.tags
            memory.listRoots(request.region).value().locations shouldBe listOf(root.location)
            memory.resolveAlias(request.region, "alias/root") shouldBe
                rejected(KeyOperation.ResolveAlias, KeyFailureCode.NotObserved)
            memory.createPrimary(creation(policy = "")).leftCode() shouldBe KeyFailureCode.InvalidInput
            memory.createPrimary(creation(region = "")).leftCode() shouldBe KeyFailureCode.InvalidInput
            memory.listRoots(request.region).value().locations.size shouldBe 1
        }

        test("alias binding is idempotent for its root and never reassigns a conflict") {
            val memory = memory()
            val first = memory.createPrimary(creation()).value().location
            val second = memory.createPrimary(creation()).value().location
            memory.bindAlias(first.root, "alias/root").value()
            memory.bindAlias(first.root, "alias/root").value()
            memory.bindAlias(second.root, "alias/root") shouldBe
                rejected(KeyOperation.BindAlias, KeyFailureCode.Conflict)
            memory.resolveAlias(first.region, "alias/root").value().location shouldBe first
            memory.bindAlias(first.root, "").leftCode() shouldBe KeyFailureCode.InvalidInput
            memory.enableRotation(first.root).value()
            memory.rotationEnabled(first.root) shouldBe true
        }

        test("related replicas share wrapping material and retain independent regional creation evidence") {
            val memory = memory()
            val primary = memory.createPrimary(creation()).value()
            val request = creation(region = "eu-west-1", policy = "replica-policy", tags = mapOf("owner" to "replica"))
            val replica = memory.createReplica(primary.location.root, request).value()
            replica.primary shouldBe primary.location
            replica.replicas shouldBe listOf(replica.location)
            memory.describe(primary.location.root).value().replicas shouldBe listOf(replica.location)
            memory.inspectReplica(primary.location.root, replica.location.root).value().location shouldBe
                replica.location
            memory.creation(replica.location.root) shouldBe request
            memory.tags(replica.location.root).value().entries shouldBe request.tags
            memory.tags(primary.location.root).value().entries shouldBe creation().tags
            val context = EncryptionContext(mapOf("scope" to "exact"))
            val generated = memory.generateDataKey(primary.location.root, context).value()
            memory.unwrap(replica.location.root, generated.wrapped, context).value().toList() shouldBe
                generated.plaintext.toList()
            val unrelated = memory.createPrimary(creation()).value()
            memory.unwrap(unrelated.location.root, generated.wrapped, context).leftCode() shouldBe
                KeyFailureCode.InvalidCiphertext
            memory.inspectReplica(unrelated.location.root, replica.location.root).leftCode() shouldBe
                KeyFailureCode.Conflict
            memory.createReplica(replica.location.root, creation(region = "eu-central-1")).leftCode() shouldBe
                KeyFailureCode.Conflict
            memory.createReplica(primary.location.root, request).value().location shouldBe replica.location
            memory.createReplica(primary.location.root, creation(region = "eu-west-1")).leftCode() shouldBe
                KeyFailureCode.Conflict
        }

        test("every expected location tag and complete relationship is checked before any deletion") {
            for (mismatch in 0..8) {
                val world = LifecycleWorld()
                val primary = world.expected.primary
                val replica = world.expected.replicas.single()
                val wrongPrimary = when (mismatch) {
                    0 -> RootExpectation(primary.location.copy(account = "other"), primary.requiredTags)
                    1 -> RootExpectation(primary.location.copy(partition = "other"), primary.requiredTags)
                    2 -> RootExpectation(primary.location.copy(region = "other"), primary.requiredTags)
                    3 -> RootExpectation(primary.location, mapOf("owner" to "other"))
                    4 -> RootExpectation(primary.location.copy(root = RootKey("missing")), primary.requiredTags)
                    5 -> replica
                    else -> primary
                }
                val replicas = when (mismatch) {
                    6 -> emptyList()
                    7 -> listOf(RootExpectation(replica.location, mapOf("owner" to "other")))
                    8 -> listOf(replica, replica)
                    else -> listOf(replica)
                }
                val result = world.memory.scheduleDeletion(ExpectedKeySet(wrongPrimary, replicas), 7)
                (result.failure != null) shouldBe true
                result.regions shouldBe emptyList()
                world.assertEnabled()
            }
        }

        test("incomplete unavailable or missing metadata and tags prevent every destructive call") {
            for (operation in listOf(KeyOperation.Describe, KeyOperation.Tags)) {
                val codes = listOf(
                    KeyFailureCode.NotObserved, KeyFailureCode.IncompleteEvidence, KeyFailureCode.Unavailable,
                )
                for (code in codes) {
                    val world = LifecycleWorld()
                    world.memory.failNext(operation, code, world.replica.root)
                    val result = world.memory.scheduleDeletion(world.expected, 7)
                    result.failure shouldBe KeyOperationFailure(operation, code)
                    result.regions shouldBe emptyList()
                    world.assertEnabled()
                }
            }
        }

        test("invalid waits mutate nothing and accepted deletion retains aliases and original dates") {
            val world = LifecycleWorld()
            for (days in listOf(6, 31)) {
                world.memory.scheduleDeletion(world.expected, days).failure?.code shouldBe KeyFailureCode.InvalidInput
                world.assertEnabled()
            }
            val first = world.memory.scheduleDeletion(world.expected, 7)
            first.failure shouldBe null
            first.regions.map { it.location } shouldBe listOf(world.replica, world.primary)
            first.regions.first().effectiveAt shouldBe start.plusSeconds(7 * 86400L)
            first.regions.last().state shouldBe DeletionState.PendingReplicaDeletion
            first.regions.last().effectiveAt shouldBe null
            world.clock.adjust(2.days)
            world.memory.scheduleDeletion(world.expected, 30).regions shouldBe first.regions
            world.memory.resolveAlias(world.primary.region, "alias/root").value().location shouldBe world.primary
            world.memory.generateDataKey(world.primary.root, EncryptionContext(emptyMap())).leftCode() shouldBe
                KeyFailureCode.Refused
        }

        test("partial regional acceptance survives retry without restarting its wait") {
            val world = LifecycleWorld()
            world.memory.failNext(KeyOperation.ScheduleDeletion, KeyFailureCode.Unavailable, world.primary.root)
            val partial = world.memory.scheduleDeletion(world.expected, 7)
            partial.regions.map { it.location } shouldBe listOf(world.replica)
            partial.failure?.code shouldBe KeyFailureCode.Unavailable
            world.memory.describe(world.primary.root).value().state shouldBe RootState.Enabled
            world.clock.adjust(2.days)
            val retried = world.memory.scheduleDeletion(world.expected, 30)
            retried.failure shouldBe null
            retried.regions.first() shouldBe partial.regions.single()
            retried.regions.last().effectiveAt shouldBe null
        }

        test("a lost scheduling reply leaves accepted state observable and retry recovers its original date") {
            val world = LifecycleWorld()
            world.memory.failNext(
                KeyOperation.ScheduleDeletion, KeyFailureCode.UncertainMutation, world.replica.root, true,
            )
            val uncertain = world.memory.scheduleDeletion(world.expected, 7)
            uncertain.failure?.code shouldBe KeyFailureCode.UncertainMutation
            uncertain.regions shouldBe emptyList()
            world.memory.describe(world.replica.root).value().state shouldBe RootState.PendingDeletion
            world.memory.describe(world.primary.root).value().state shouldBe RootState.Enabled
            world.clock.adjust(2.days)
            val recovered = world.memory.scheduleDeletion(world.expected, 30)
            recovered.regions.first().effectiveAt shouldBe start.plusSeconds(7 * 86400L)
            recovered.failure shouldBe null
        }

        test("a lost create reply leaves an observed root and another create remains distinct") {
            val memory = memory()
            memory.failNext(KeyOperation.CreatePrimary, KeyFailureCode.UncertainMutation, afterSuccess = true)
            memory.createPrimary(creation()).leftCode() shouldBe KeyFailureCode.UncertainMutation
            val first = memory.listRoots("eu-west-2").value().locations.single()
            val second = memory.createPrimary(creation()).value().location
            (first != second) shouldBe true
            memory.listRoots("eu-west-2").value().locations.size shouldBe 2
        }

        test("lost alias rotation and replica replies retain evidence for explicit reconciliation") {
            val memory = memory()
            val primary = memory.createPrimary(creation()).value().location
            memory.failNext(KeyOperation.BindAlias, KeyFailureCode.UncertainMutation, primary.root, true)
            memory.bindAlias(primary.root, "alias/root").leftCode() shouldBe KeyFailureCode.UncertainMutation
            memory.resolveAlias(primary.region, "alias/root").value().location shouldBe primary
            memory.failNext(KeyOperation.EnableRotation, KeyFailureCode.UncertainMutation, primary.root, true)
            memory.enableRotation(primary.root).leftCode() shouldBe KeyFailureCode.UncertainMutation
            memory.rotationEnabled(primary.root) shouldBe true
            memory.failNext(KeyOperation.CreateReplica, KeyFailureCode.UncertainMutation, primary.root, true)
            memory.createReplica(primary.root, creation(region = "eu-west-1")).leftCode() shouldBe
                KeyFailureCode.UncertainMutation
            val replica = memory.listRoots("eu-west-1").value().locations.single()
            memory.inspectReplica(primary.root, replica.root).value().location shouldBe replica
        }

        test("pending replica creation stays retryable until explicitly observed enabled") {
            val world = LifecycleWorld()
            world.memory.setState(world.replica.root, RootState.Creating)
            world.memory.inspectReplica(world.primary.root, world.replica.root).value().state shouldBe
                RootState.Creating
            world.memory.generateDataKey(world.replica.root, EncryptionContext(emptyMap())).leftCode() shouldBe
                KeyFailureCode.Unavailable
            world.memory.scheduleDeletion(world.expected, 7).failure?.code shouldBe KeyFailureCode.Unavailable
            world.memory.describe(world.primary.root).value().state shouldBe RootState.Enabled
            world.memory.setState(world.replica.root, RootState.Enabled)
            world.memory.scheduleDeletion(world.expected, 7).failure shouldBe null
        }

        test("single regional pending deletion preserves even an unknown accepted date") {
            val memory = memory()
            val primary = memory.createPrimary(creation()).value().location
            val expected = ExpectedKeySet(RootExpectation(primary, creation().tags), emptyList())
            memory.setState(primary.root, RootState.PendingDeletion)
            val result = memory.scheduleDeletion(expected, 7)
            result.failure shouldBe null
            result.regions.single().state shouldBe DeletionState.PendingDeletion
            result.regions.single().effectiveAt shouldBe null
        }

        test("replicas inherit automatic rotation and enabling the primary updates existing replicas") {
            val memory = memory()
            val primary = memory.createPrimary(creation()).value().location
            val first = memory.createReplica(primary.root, creation(region = "eu-west-1")).value().location
            memory.enableRotation(primary.root).value()
            memory.rotationEnabled(primary.root) shouldBe true
            memory.rotationEnabled(first.root) shouldBe true
            val second = memory.createReplica(primary.root, creation(region = "eu-central-1")).value().location
            memory.rotationEnabled(second.root) shouldBe true
        }

        test("rotation can be enabled only through the primary root") {
            val world = LifecycleWorld()
            world.memory.enableRotation(world.replica.root).leftOrNull()?.code shouldBe KeyFailureCode.Refused
            world.memory.rotationEnabled(world.primary.root) shouldBe false
            world.memory.rotationEnabled(world.replica.root) shouldBe false
            world.memory.enableRotation(world.primary.root).value()
            world.memory.rotationEnabled(world.replica.root) shouldBe true
        }

        test("unusable primary states cannot enable rotation or create a replica") {
            for (state in RootState.entries.filter { it != RootState.Enabled }) {
                val memory = memory()
                val primary = memory.createPrimary(creation()).value().location
                memory.setState(primary.root, state)
                val code = if (state in listOf(
                        RootState.Creating, RootState.Updating, RootState.Unavailable, RootState.Unknown,
                    )
                ) KeyFailureCode.Unavailable else KeyFailureCode.Refused
                memory.enableRotation(primary.root).leftOrNull()?.code shouldBe code
                memory.createReplica(primary.root, creation(region = "eu-west-1")).leftOrNull()?.code shouldBe code
                memory.rotationEnabled(primary.root) shouldBe false
                memory.listRoots("eu-west-1").value().locations shouldBe emptyList()
            }
        }

        test("alias discovery faults target its bound root and never imply destruction") {
            val memory = memory()
            val root = memory.createPrimary(creation()).value().location
            memory.bindAlias(root.root, "alias/root").value()
            memory.failNext(KeyOperation.ResolveAlias, KeyFailureCode.NotObserved, root.root)
            memory.resolveAlias(root.region, "alias/root").leftOrNull()?.code shouldBe KeyFailureCode.NotObserved
            memory.resolveAlias(root.region, "alias/root").value().location shouldBe root
            memory.listRoots(root.region).value().locations shouldBe listOf(root)
        }

        test("after success data faults are consumed for the same operation and root") {
            val memory = memory()
            val root = memory.createPrimary(creation()).value().location.root
            val context = EncryptionContext(mapOf("scope" to "exact"))
            val wrapped = memory.wrap(root, byteArrayOf(1, 2, 3), context).value()
            val calls: List<Pair<KeyOperation, () -> Either<KeyOperationFailure, Unit>>> = listOf(
                KeyOperation.Generate to { memory.generateDataKey(root, context).map { it.plaintext.fill(0) } },
                KeyOperation.Wrap to { memory.wrap(root, byteArrayOf(1, 2, 3), context).map { Unit } },
                KeyOperation.Unwrap to { memory.unwrap(root, wrapped, context).map { it.fill(0) } },
            )
            for ((operation, call) in calls) {
                memory.failNext(operation, KeyFailureCode.Unavailable, root, afterSuccess = true)
                call().leftOrNull()?.code shouldBe KeyFailureCode.Unavailable
                call().value() shouldBe Unit
            }
        }

        test("losing a wrap reply preserves caller supplied plaintext") {
            val memory = memory()
            val root = memory.createPrimary(creation()).value().location.root
            val plaintext = byteArrayOf(1, 2, 3)
            memory.failNext(KeyOperation.Wrap, KeyFailureCode.Unavailable, root, afterSuccess = true)
            memory.wrap(root, plaintext, EncryptionContext(emptyMap())).leftOrNull()?.code shouldBe
                KeyFailureCode.Unavailable
            plaintext.toList() shouldBe listOf<Byte>(1, 2, 3)
        }
    }
}

private val start: Instant = Instant.parse("2026-10-29T12:00:00Z")

private fun memory(clock: TestClock = TestClock(start)) = InMemoryKeyOperations(clock, "account", "aws")

private fun creation(
    region: String = "eu-west-2",
    policy: String = "explicit-policy",
    tags: Map<String, String> = mapOf("owner" to "expected"),
) = RootCreation(region, policy, "description", tags)

private class LifecycleWorld {
    val clock = TestClock(start)
    val memory = memory(clock)
    val primary = memory.createPrimary(creation()).value().location
    val replica = memory.createReplica(primary.root, creation(region = "eu-west-1")).value().location
    val expected = ExpectedKeySet(
        RootExpectation(primary, creation().tags), listOf(RootExpectation(replica, creation().tags)),
    )

    init {
        memory.bindAlias(primary.root, "alias/root").value()
    }

    fun assertEnabled() {
        memory.describe(primary.root).value().state shouldBe RootState.Enabled
        memory.describe(replica.root).value().state shouldBe RootState.Enabled
    }
}

private fun <E, A> Either<E, A>.value(): A = (this as Either.Right).value

private fun <A> Either<KeyOperationFailure, A>.leftCode(): KeyFailureCode = (this as Either.Left).value.code

private fun rejected(operation: KeyOperation, code: KeyFailureCode) = Either.Left(KeyOperationFailure(operation, code))
