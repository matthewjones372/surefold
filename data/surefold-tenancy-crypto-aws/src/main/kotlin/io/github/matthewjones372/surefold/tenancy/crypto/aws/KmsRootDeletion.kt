package io.github.matthewjones372.surefold.tenancy.crypto.aws

import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.ensure
import dev.surefold.fail.ensureNotNull
import io.github.matthewjones372.surefold.tenancy.crypto.DeletionProgress
import io.github.matthewjones372.surefold.tenancy.crypto.DeletionState
import io.github.matthewjones372.surefold.tenancy.crypto.ExpectedKeySet
import io.github.matthewjones372.surefold.tenancy.crypto.KeyFailureCode
import io.github.matthewjones372.surefold.tenancy.crypto.KeyOperation
import io.github.matthewjones372.surefold.tenancy.crypto.KeyOperationFailure
import io.github.matthewjones372.surefold.tenancy.crypto.RegionalDeletion
import io.github.matthewjones372.surefold.tenancy.crypto.RootLocation
import io.github.matthewjones372.surefold.tenancy.crypto.RootMetadata
import io.github.matthewjones372.surefold.tenancy.crypto.RootState
import software.amazon.awssdk.services.kms.model.KmsInvalidStateException
import software.amazon.awssdk.services.kms.model.ScheduleKeyDeletionRequest
import java.time.Instant

internal class KmsRootDeletion(private val observations: KmsObservations) {
    fun scheduleDeletion(expected: ExpectedKeySet, waitingDays: Int): DeletionProgress {
        val preflight = preflight(expected, waitingDays)
        if (preflight is Either.Left) return DeletionProgress(emptyList(), preflight.value)
        val observed = (preflight as Either.Right).value
        var accepted = emptyList<RegionalDeletion>()
        for (member in observed.drop(1) + observed.take(1)) {
            when (val result = schedule(member, waitingDays)) {
                is Either.Left -> return DeletionProgress(accepted, result.value)
                is Either.Right -> accepted = accepted + result.value
            }
        }
        return DeletionProgress(accepted, null)
    }

    private fun preflight(
        expected: ExpectedKeySet,
        waitingDays: Int,
    ): Either<KeyOperationFailure, List<KmsObservation>> = kmsCall(OP) {
        attempt {
            ensure(waitingDays in MIN_WAIT..MAX_WAIT) { deletionFailure(KeyFailureCode.InvalidInput) }
            val members = listOf(expected.primary) + expected.replicas
            val identities = members.map { member ->
                val identity = KmsIdentity.parse(member.location.root, OP).bind()
                ensure(identity.location == member.location) { conflict(OP) }
                identity
            }
            val primary = expected.primary.location
            ensure(
                members.map { it.location.root }.distinct().size == members.size &&
                    members.map { it.location.region }.distinct().size == members.size &&
                    identities.all {
                        it.location.account == primary.account && it.location.partition == primary.partition &&
                            it.keyId == identities.first().keyId
                    },
            ) { conflict(OP) }
            val replicas = expected.replicas.map { it.location }.toSet()
            members.map { member ->
                val observation = observations.describe(member.location.root).bind()
                val metadata = observation.metadata
                val singleRegion = metadata.primary == null && replicas.isEmpty() && metadata.location == primary
                ensure(
                    (singleRegion || metadata.primary == primary) && metadata.replicas.toSet() == replicas,
                ) { conflict(OP) }
                val tags = observations.tags(member.location.root).bind().entries
                ensure(member.requiredTags.all { (key, value) -> tags[key] == value }) { conflict(OP) }
                ensure(metadata.state !in retryableDeletionStates) { deletionFailure(KeyFailureCode.Unavailable) }
                if (metadata.state == RootState.PendingReplicaDeletion) {
                    ensure(metadata.primary == metadata.location) { conflict(OP) }
                }
                observation
            }
        }
    }

    private fun schedule(observed: KmsObservation, waitingDays: Int): Either<KeyOperationFailure, RegionalDeletion> {
        val metadata = observed.metadata
        if (metadata.state == RootState.PendingDeletion || metadata.state == RootState.PendingReplicaDeletion) {
            return pending(observed)
        }
        return kmsCall(OP, mutation = true) {
            attempt {
                val location = metadata.location
                val sdk = observations.client(location.region, OP).bind()
                try {
                    val response = sdk.scheduleKeyDeletion(
                        ScheduleKeyDeletionRequest.builder().keyId(location.root.id)
                            .pendingWindowInDays(waitingDays).build(),
                    )
                    ensure(response.keyId() == location.root.id) { deletionFailure(KeyFailureCode.UncertainMutation) }
                    deletionEvidence(
                        location, response.keyStateAsString(), response.deletionDate(),
                        metadata.primary == location, KeyFailureCode.UncertainMutation,
                    ).bind()
                } catch (race: KmsInvalidStateException) {
                    propagateCancellation(race)
                    val refreshed = observations.describe(location.root).bind()
                    ensure(sameTopology(metadata, refreshed.metadata)) { conflict(OP) }
                    pending(refreshed).bind()
                }
            }
        }
    }

    private fun pending(observed: KmsObservation): Either<KeyOperationFailure, RegionalDeletion> = attempt {
        val metadata = observed.metadata
        ensure(metadata.state !in retryableDeletionStates) { deletionFailure(KeyFailureCode.Unavailable) }
        deletionEvidence(
            metadata.location, observed.provider.keyStateAsString(), observed.provider.deletionDate(),
            metadata.primary == metadata.location, KeyFailureCode.Refused,
        ).bind()
    }

    companion object {
        private val OP = KeyOperation.ScheduleDeletion
        private const val MIN_WAIT = 7
        private const val MAX_WAIT = 30
    }
}

private fun sameTopology(before: RootMetadata, after: RootMetadata) =
    before.location == after.location && before.primary == after.primary &&
        before.replicas.toSet() == after.replicas.toSet()

private fun deletionEvidence(
    location: RootLocation,
    state: String?,
    date: Instant?,
    primary: Boolean,
    failure: KeyFailureCode,
): Either<KeyOperationFailure, RegionalDeletion> = attempt {
    val accepted = ensureNotNull(
        when (state) {
            "PendingDeletion" -> RegionalDeletion(location, DeletionState.PendingDeletion, date)

            "PendingReplicaDeletion" -> if (primary) {
                RegionalDeletion(location, DeletionState.PendingReplicaDeletion, null)
            } else null

            else -> null
        },
    ) { deletionFailure(failure) }
    accepted
}

private fun deletionFailure(code: KeyFailureCode) = KeyOperationFailure(KeyOperation.ScheduleDeletion, code)
private val retryableDeletionStates =
    setOf(RootState.Creating, RootState.Updating, RootState.Unavailable, RootState.Unknown)
