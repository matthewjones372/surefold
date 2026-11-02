package io.github.matthewjones372.surefold.tenancy.crypto.aws

import dev.surefold.fail.map
import io.github.matthewjones372.surefold.tenancy.crypto.EncryptionContext
import io.github.matthewjones372.surefold.tenancy.crypto.ExpectedKeySet
import io.github.matthewjones372.surefold.tenancy.crypto.KeyOperations
import io.github.matthewjones372.surefold.tenancy.crypto.RootCreation
import io.github.matthewjones372.surefold.tenancy.crypto.RootKey
import software.amazon.awssdk.services.kms.KmsClient

/** Regional clients are borrowed; creation clients require verified single-attempt SDK configuration. */
class AwsKeyOperations(
    clients: Map<String, KmsClient>,
    creationClients: Map<String, KmsClient> = emptyMap(),
    preferredDataRegion: String? = null,
) : KeyOperations {
    private val observations = KmsObservations(clients)
    private val data = KmsDataOperations(observations, preferredDataRegion)
    private val creation = KmsRootCreation(observations, creationClients)
    private val replicas = KmsRootReplicas(observations, creationClients)
    private val deletion = KmsRootDeletion(observations)

    override fun describe(root: RootKey) = observations.describe(root).map { it.metadata }
    override fun listRoots(region: String) = observations.listRoots(region)
    override fun resolveAlias(region: String, alias: String) =
        observations.resolveAlias(region, alias).map { it.metadata }
    override fun tags(root: RootKey) = observations.tags(root)
    override fun generateDataKey(root: RootKey, context: EncryptionContext) = data.generateDataKey(root, context)
    override fun wrap(root: RootKey, plaintext: ByteArray, context: EncryptionContext) =
        data.wrap(root, plaintext, context)
    override fun unwrap(root: RootKey, wrapped: ByteArray, context: EncryptionContext) =
        data.unwrap(root, wrapped, context)
    override fun createPrimary(request: RootCreation) = creation.createPrimary(request)
    override fun bindAlias(root: RootKey, alias: String) = creation.bindAlias(root, alias)
    override fun enableRotation(root: RootKey) = creation.enableRotation(root)
    override fun createReplica(primary: RootKey, request: RootCreation) = replicas.createReplica(primary, request)
    override fun inspectReplica(primary: RootKey, replica: RootKey) = replicas.inspectReplica(primary, replica)
    override fun scheduleDeletion(expected: ExpectedKeySet, waitingDays: Int) =
        deletion.scheduleDeletion(expected, waitingDays)
}
