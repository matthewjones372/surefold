package dev.surefold.tenancy.crypto.aws

import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.ensure
import dev.surefold.fail.ensureNotNull
import dev.surefold.tenancy.crypto.KeyOperation
import dev.surefold.tenancy.crypto.KeyOperationFailure
import dev.surefold.tenancy.crypto.RootKey
import dev.surefold.tenancy.crypto.RootLocation
import dev.surefold.tenancy.crypto.RootMetadata
import dev.surefold.tenancy.crypto.RootState
import software.amazon.awssdk.services.kms.model.KeyMetadata
import software.amazon.awssdk.services.kms.model.MultiRegionKey

/** Provider dates remain available for deletion without extending the provider-neutral metadata. */
internal class KmsObservation(val metadata: RootMetadata, val provider: KeyMetadata) {
    override fun toString(): String = "KmsObservation(state=${metadata.state})"
}

internal fun observeKmsMetadata(
    raw: KeyMetadata?,
    region: String,
    expected: KmsIdentity? = null,
): Either<KeyOperationFailure, KmsObservation> = attempt {
    val op = KeyOperation.Describe
    val provider = ensureNotNull(raw) { incomplete(op) }
    val arn = ensureNotNull(provider.arn()) { incomplete(op) }
    val identity = expected ?: ensureNotNull(KmsIdentity.parse(RootKey(arn), op).getOrNull()) { incomplete(op) }
    identity.verify(arn, provider.keyId(), provider.awsAccountId(), op).bind()
    ensure(identity.location.region == region) { conflict(op) }
    val state = ensureNotNull(provider.keyStateAsString()) { incomplete(op) }
    ensure(state.isNotBlank()) { incomplete(op) }
    val multiRegion = ensureNotNull(provider.multiRegion()) { incomplete(op) }
    ensure(multiRegion == identity.keyId.startsWith("mrk-")) { conflict(op) }
    val configuration = provider.multiRegionConfiguration()
    val metadata = if (!multiRegion) {
        ensure(configuration == null) { conflict(op) }
        RootMetadata(identity.location, rootState(state), null, emptyList())
    } else {
        val topology = ensureNotNull(configuration) { incomplete(op) }
        ensure(topology.hasReplicaKeys()) { incomplete(op) }
        val primary = related(identity, topology.primaryKey()).bind()
        val replicas = topology.replicaKeys().map { related(identity, it).bind() }
        val locations = listOf(primary) + replicas
        ensure(locations.map { it.region }.distinct().size == locations.size) { conflict(op) }
        when (topology.multiRegionKeyTypeAsString()) {
            "PRIMARY" -> ensure(primary == identity.location) { conflict(op) }
            "REPLICA" -> ensure(identity.location in replicas) { conflict(op) }
            else -> fail(incomplete(op))
        }
        RootMetadata(identity.location, rootState(state), primary, replicas)
    }
    KmsObservation(metadata, provider)
}

private fun related(identity: KmsIdentity, raw: MultiRegionKey?): Either<KeyOperationFailure, RootLocation> = attempt {
    val op = KeyOperation.Describe
    val relation = ensureNotNull(raw) { incomplete(op) }
    val arn = ensureNotNull(relation.arn()) { incomplete(op) }
    val candidate = ensureNotNull(KmsIdentity.parse(RootKey(arn), op).getOrNull()) { incomplete(op) }
    val reported = ensureNotNull(relation.region()) { incomplete(op) }
    ensure(reported.isNotBlank()) { incomplete(op) }
    ensure(
        candidate.location.region == reported && candidate.location.account == identity.location.account &&
            candidate.location.partition == identity.location.partition && candidate.keyId == identity.keyId,
    ) { conflict(op) }
    candidate.location
}

private fun rootState(value: String): RootState = when (value) {
    "Creating" -> RootState.Creating
    "Enabled" -> RootState.Enabled
    "Disabled" -> RootState.Disabled
    "Updating" -> RootState.Updating
    "PendingDeletion" -> RootState.PendingDeletion
    "PendingReplicaDeletion" -> RootState.PendingReplicaDeletion
    "Unavailable" -> RootState.Unavailable
    else -> RootState.Unknown
}
