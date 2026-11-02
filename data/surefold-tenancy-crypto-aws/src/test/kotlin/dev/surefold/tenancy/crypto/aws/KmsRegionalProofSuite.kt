package dev.surefold.tenancy.crypto.aws

import dev.surefold.Shared
import dev.surefold.fail.Either
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import dev.surefold.tenancy.crypto.KeyFailureCode
import dev.surefold.tenancy.crypto.KeyOperation
import dev.surefold.tenancy.crypto.KeyOperationFailure
import dev.surefold.tenancy.crypto.KeyOperations
import dev.surefold.tenancy.crypto.KeyTags
import dev.surefold.tenancy.crypto.RootKey
import dev.surefold.tenancy.crypto.RootLocation
import dev.surefold.tenancy.crypto.RootMetadata
import dev.surefold.tenancy.crypto.RootState

object KmsRegionalProofSuite : Suite() {
    init {
        test("an invalid manual proof throws a closed failure so JavaExec cannot report success") {
            shouldThrow<IllegalStateException>({ KmsRegionalProof.main(emptyArray()) }) {
                it.message shouldBe "proof invalid-input"
                it.cause shouldBe null
            }
        }
        test("recorded roots can be observed while pending even when current relationships have disappeared") {
            val client = ProofObservations()
            val lines = Shared(emptyList<String>())
            observeRecorded(client, lines)
            client.queried.peek() shouldBe listOf(proofPrimary, proofReplica)
            lines.peek() shouldBe listOf(
                "${proofPrimary.id} PendingReplicaDeletion", "${proofReplica.id} PendingDeletion",
            )
        }
        test("an unresolved root does not erase the remaining recorded inventory or prove destruction") {
            val client = ProofObservations(missing = true)
            val lines = Shared(emptyList<String>())
            shouldThrow<IllegalStateException>({ observeRecorded(client, lines) }) {
                it.message shouldBe "proof incomplete-evidence"
                it.cause shouldBe null
            }
            client.queried.peek() shouldBe listOf(proofPrimary, proofReplica)
            lines.peek() shouldBe listOf(
                "${proofPrimary.id} Describe NotObserved", "${proofReplica.id} PendingDeletion",
            )
        }
    }
}

private val proofPrimary = RootKey("arn:aws:kms:eu-west-2:123456789012:key/mrk-${"c".repeat(32)}")
private val proofReplica = RootKey(proofPrimary.id.replace("eu-west-2", "eu-west-1"))
private val observedTags = mapOf("surefold:proof" to "opaque", "surefold:disposable" to "true")
private fun observeRecorded(operations: KeyOperations, lines: Shared<List<String>>) {
    observeRecordedRoots(operations, listOf(proofPrimary, proofReplica), observedTags) { line ->
        lines.update { it + line }
    }
}
private class ProofObservations(private val missing: Boolean = false) : KeyOperations by AwsKeyOperations(emptyMap()) {
    val queried = Shared(emptyList<RootKey>())
    override fun describe(root: RootKey): Either<KeyOperationFailure, RootMetadata> {
        queried.update { it + root }
        if (missing && root == proofPrimary) {
            return Either.Left(KeyOperationFailure(KeyOperation.Describe, KeyFailureCode.NotObserved))
        }
        val region = if (root == proofPrimary) "eu-west-2" else "eu-west-1"
        val location = RootLocation(root, "123456789012", "aws", region)
        val state = if (root == proofPrimary) RootState.PendingReplicaDeletion else RootState.PendingDeletion
        return RootMetadata(location, state, location, emptyList()).right()
    }
    override fun tags(root: RootKey) = KeyTags(observedTags).right()
}
