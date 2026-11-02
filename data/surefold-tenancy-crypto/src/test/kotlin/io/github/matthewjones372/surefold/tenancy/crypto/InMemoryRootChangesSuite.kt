package io.github.matthewjones372.surefold.tenancy.crypto

import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe

object InMemoryRootChangesSuite : Suite() {
    init {
        test("a before fault prevents mutation and is consumed only for its operation and root") {
            val world = ChangeWorld()
            world.fault(KeyOperation.EnableRotation, KeyFailureCode.Unavailable, world.primary.location.root)
            val observed = world.changes.observe(KeyOperation.Describe, world.primary.location.root) { _, held ->
                held.rotation
            }
            observed.value() shouldBe false
            world.rotate(world.replica.location.root).value()
            world.rotate(world.primary.location.root) shouldBe
                rejected(KeyOperation.EnableRotation, KeyFailureCode.Unavailable)
            world.store.state.peek().roots.getValue(world.primary.location.root).rotation shouldBe false
            world.rotate(world.primary.location.root).value()
            world.store.state.peek().roots.getValue(world.primary.location.root).rotation shouldBe true
        }

        test("an after success fault retains committed mutation and is consumed once") {
            val world = ChangeWorld()
            val root = world.primary.location.root
            world.fault(KeyOperation.EnableRotation, KeyFailureCode.UncertainMutation, root, true)
            world.rotate(root) shouldBe rejected(KeyOperation.EnableRotation, KeyFailureCode.UncertainMutation)
            world.store.state.peek().roots.getValue(world.primary.location.root).rotation shouldBe true
            world.rotate(world.primary.location.root).value()
            world.store.state.peek().faults shouldBe emptyList()
        }

        test("failed operations retain their after success fault for the next successful mutation") {
            val world = ChangeWorld()
            val root = world.primary.location.root
            world.fault(KeyOperation.EnableRotation, KeyFailureCode.UncertainMutation, root, true)
            world.changes.change(KeyOperation.EnableRotation, root) { current, _ ->
                current to KeyOperationFailure(KeyOperation.EnableRotation, KeyFailureCode.Refused).left()
            } shouldBe rejected(KeyOperation.EnableRotation, KeyFailureCode.Refused)
            world.store.state.peek().faults.size shouldBe 1
            world.rotate(root) shouldBe rejected(KeyOperation.EnableRotation, KeyFailureCode.UncertainMutation)
        }

        test("ordered wildcard faults leave later outcomes available for later calls") {
            val world = ChangeWorld()
            world.fault(KeyOperation.Describe, KeyFailureCode.IncompleteEvidence)
            world.fault(KeyOperation.Describe, KeyFailureCode.NotObserved)
            for (code in listOf(KeyFailureCode.IncompleteEvidence, KeyFailureCode.NotObserved)) {
                val observed = world.changes.observe(KeyOperation.Describe, world.replica.location.root) { _, held ->
                    held.location
                }
                observed shouldBe rejected(KeyOperation.Describe, code)
            }
            world.changes.preflight(world.expected).value()
        }

        test("missing observations never create a root or claim destruction") {
            val world = ChangeWorld()
            val observed = world.changes.observe(KeyOperation.Describe, RootKey("missing")) { current, held ->
                current.metadata(held)
            }
            observed shouldBe rejected(KeyOperation.Describe, KeyFailureCode.NotObserved)
            world.store.state.peek().roots.size shouldBe 2
        }

        test("every regional metadata and tag failure blocks complete set preflight") {
            for (operation in listOf(KeyOperation.Describe, KeyOperation.Tags)) {
                for (rootIndex in 0..1) {
                    val codes = listOf(
                        KeyFailureCode.NotObserved, KeyFailureCode.IncompleteEvidence, KeyFailureCode.Unavailable,
                    )
                    for (code in codes) {
                        val world = ChangeWorld()
                        val root = listOf(world.primary, world.replica)[rootIndex].location.root
                        world.fault(operation, code, root)
                        world.changes.preflight(world.expected) shouldBe rejected(operation, code)
                        world.store.state.peek().roots.values.map { it.state } shouldBe
                            listOf(RootState.Enabled, RootState.Enabled)
                    }
                }
            }
        }

        test("full locations required tag subsets duplicate roots and unexpected replicas are checked") {
            for (mismatch in 0..9) {
                val world = ChangeWorld()
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
                    9 -> listOf(RootExpectation(replica.location.copy(account = "other"), replica.requiredTags))
                    else -> listOf(replica)
                }
                (world.changes.preflight(ExpectedKeySet(wrongPrimary, replicas)) is Either.Left) shouldBe true
            }
        }

        test("tag subset evidence permits independent regional values without application policy") {
            val world = ChangeWorld()
            world.store.state.update { current ->
                val creation = RootCreation("replica-region", "regional-policy", "", mapOf("different" to "value"))
                val replica = world.replica.copy(creation = creation)
                current.copy(roots = current.roots + (replica.location.root to replica))
            }
            val replica = RootExpectation(world.replica.location, mapOf("different" to "value"))
            val expected = ExpectedKeySet(world.expected.primary, listOf(replica))
            world.changes.preflight(expected).value() shouldBe Unit
        }

        test("missing root or changed relationship in a later snapshot refuses destructive revalidation") {
            val world = ChangeWorld()
            world.changes.preflight(world.expected).value()
            val original = world.store.state.peek()
            val missing = original.copy(roots = original.roots - world.replica.location.root)
            world.changes.validate(missing, world.expected)?.code shouldBe KeyFailureCode.NotObserved
            val unrelated = world.replica.copy(primary = world.replica.location.root)
            val changed = original.copy(roots = original.roots + (unrelated.location.root to unrelated))
            world.changes.validate(changed, world.expected)?.code shouldBe KeyFailureCode.Conflict
        }

        test("transient states stay retryable while accepted pending states remain valid evidence") {
            for (state in RootState.entries) {
                val world = ChangeWorld()
                world.store.state.update { current ->
                    val held = world.replica.copy(state = state)
                    current.copy(roots = current.roots + (held.location.root to held))
                }
                val retryable = state in listOf(
                    RootState.Creating, RootState.Updating, RootState.Unavailable, RootState.Unknown,
                )
                world.changes.preflight(world.expected).leftOrNull()?.code shouldBe
                    if (retryable) KeyFailureCode.Unavailable else null
            }
        }

        test("lost data replies erase only unreturned output and consume only matching after faults") {
            val world = ChangeWorld()
            val root = world.primary.location.root
            val output = byteArrayOf(1, 2, 3)
            world.fault(KeyOperation.Wrap, KeyFailureCode.Refused, root)
            world.fault(KeyOperation.Wrap, KeyFailureCode.Unavailable, world.replica.location.root, true)
            world.fault(KeyOperation.Wrap, KeyFailureCode.UncertainMutation, root, true)
            world.changes.afterData(KeyOperation.Wrap, root, output.right()) { it.fill(0) } shouldBe
                rejected(KeyOperation.Wrap, KeyFailureCode.UncertainMutation)
            output.toList() shouldBe listOf<Byte>(0, 0, 0)
            world.store.state.peek().faults.size shouldBe 2
            world.changes.afterData(KeyOperation.Wrap, root, Unit.right()) {}.value() shouldBe Unit
            world.store.state.peek().faults.size shouldBe 2
        }

        test("data cleanup runs after fault consumption has committed even if cleanup throws") {
            val world = ChangeWorld()
            val root = world.primary.location.root
            val generated = GeneratedKey(byteArrayOf(1), byteArrayOf(2))
            world.fault(KeyOperation.Generate, KeyFailureCode.Unavailable, root, true)
            var cleanupThrew = false
            try {
                world.changes.afterData(KeyOperation.Generate, root, generated.right()) {
                    it.plaintext.fill(0)
                    it.wrapped.fill(0)
                    error("cleanup rejected")
                }
            } catch (_: IllegalStateException) {
                cleanupThrew = true
            }
            cleanupThrew shouldBe true
            generated.plaintext.toList() shouldBe listOf<Byte>(0)
            generated.wrapped.toList() shouldBe listOf<Byte>(0)
            world.store.state.peek().faults shouldBe emptyList()
        }

        test("ordinary data failures do not consume an after success fault or run output cleanup") {
            val world = ChangeWorld()
            val root = world.primary.location.root
            world.fault(KeyOperation.Unwrap, KeyFailureCode.Unavailable, root, true)
            var erased = false
            val failed: Either<KeyOperationFailure, ByteArray> =
                KeyOperationFailure(KeyOperation.Unwrap, KeyFailureCode.InvalidCiphertext).left()
            world.changes.afterData(KeyOperation.Unwrap, root, failed) { erased = true } shouldBe failed
            erased shouldBe false
            world.store.state.peek().faults.size shouldBe 1
        }
    }
}

private class ChangeWorld {
    val primary = changeRoot("primary", "primary-region")
    val replica = changeRoot("replica", "replica-region").copy(primary = primary.location.root, key = primary.key)
    val store = InMemoryKeyStore(
        InMemoryKeyState(roots = mapOf(primary.location.root to primary, replica.location.root to replica)),
    )
    val changes = InMemoryRootChanges(store)
    val expected = ExpectedKeySet(
        RootExpectation(primary.location, mapOf("owner" to "expected")),
        listOf(RootExpectation(replica.location, mapOf("owner" to "expected"))),
    )

    fun fault(operation: KeyOperation, code: KeyFailureCode, root: RootKey? = null, after: Boolean = false) {
        store.state.update { it.copy(faults = it.faults + InMemoryFault(operation, code, root, after)) }
    }

    fun rotate(root: RootKey) = changes.change(KeyOperation.EnableRotation, root) { current, held ->
        current.copy(roots = current.roots + (root to held.copy(rotation = true))) to Unit.right()
    }
}

private fun changeRoot(id: String, region: String): InMemoryRoot {
    val root = RootKey(id)
    return InMemoryRoot(
        RootLocation(root, "account", "partition", region), root,
        RootCreation(region, "policy", "", mapOf("owner" to "expected", "extra" to "allowed")), InMemoryWrappingKey(),
    )
}

private fun <E, A> Either<E, A>.value(): A = (this as Either.Right).value

private fun rejected(operation: KeyOperation, code: KeyFailureCode) = Either.Left(KeyOperationFailure(operation, code))
