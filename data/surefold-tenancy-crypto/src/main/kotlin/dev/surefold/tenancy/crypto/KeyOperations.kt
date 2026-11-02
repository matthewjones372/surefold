package dev.surefold.tenancy.crypto

import dev.surefold.fail.Either
import java.time.Instant
import java.util.Collections

class EncryptionContext(entries: Map<String, String>) {
    val entries: Map<String, String> = Collections.unmodifiableMap(entries.toMap())

    override fun toString(): String = "EncryptionContext(redacted)"
}

enum class KeyOperation {
    Describe,
    ListRoots,
    ResolveAlias,
    Tags,
    Generate,
    Wrap,
    Unwrap,
    CreatePrimary,
    BindAlias,
    EnableRotation,
    CreateReplica,
    InspectReplica,
    ScheduleDeletion,
}

/** Codes are closed values because provider messages can contain policy, context or material. */
enum class KeyFailureCode {
    InvalidInput,
    InvalidCiphertext,
    Refused,
    Unavailable,
    NotObserved,
    IncompleteEvidence,
    Conflict,
    UncertainMutation,
}

data class KeyOperationFailure(val operation: KeyOperation, val code: KeyFailureCode)

data class RootLocation(val root: RootKey, val account: String, val partition: String, val region: String)

enum class RootState {
    Creating,
    Enabled,
    Disabled,
    Updating,
    PendingDeletion,
    PendingReplicaDeletion,
    Unavailable,
    Unknown,
}

class RootMetadata(
    val location: RootLocation,
    val state: RootState,
    val primary: RootLocation?,
    replicas: List<RootLocation>,
) {
    val replicas: List<RootLocation> = Collections.unmodifiableList(replicas.toList())

    override fun toString(): String = "RootMetadata(state=$state)"
}

/** A successful tag read is complete; truncated or conflicting evidence is a failure. */
class KeyTags(entries: Map<String, String>) {
    val entries: Map<String, String> = Collections.unmodifiableMap(entries.toMap())

    override fun toString(): String = "KeyTags(redacted)"
}

class RootCreation(
    val region: String,
    val policy: String,
    val description: String,
    tags: Map<String, String>,
) {
    val tags: Map<String, String> = Collections.unmodifiableMap(tags.toMap())

    override fun toString(): String = "RootCreation(redacted)"
}

class RootExpectation(val location: RootLocation, requiredTags: Map<String, String>) {
    val requiredTags: Map<String, String> = Collections.unmodifiableMap(requiredTags.toMap())

    override fun toString(): String = "RootExpectation(redacted)"
}

/** Locations include account and partition; the entire relationship and tag subsets must match. */
class ExpectedKeySet(val primary: RootExpectation, replicas: List<RootExpectation>) {
    val replicas: List<RootExpectation> = Collections.unmodifiableList(replicas.toList())

    override fun toString(): String = "ExpectedKeySet(redacted)"
}

class RootListing(locations: List<RootLocation>) {
    val locations: List<RootLocation> = Collections.unmodifiableList(locations.toList())

    override fun toString(): String = "RootListing(observed=${locations.size})"
}

enum class DeletionState { PendingDeletion, PendingReplicaDeletion }

data class RegionalDeletion(val location: RootLocation, val state: DeletionState, val effectiveAt: Instant?)

/** Earlier acceptances survive a later failure; an unknown date is never inferred from the wait. */
class DeletionProgress(regions: List<RegionalDeletion>, val failure: KeyOperationFailure?) {
    val regions: List<RegionalDeletion> = Collections.unmodifiableList(regions.toList())

    override fun toString(): String = "DeletionProgress(accepted=${regions.size}, failure=$failure)"
}

/** Returned arrays belong to the caller for erasure; implementations retain no plaintext copies. */
interface DataKeyOperations {
    /** Generates exactly 32 plaintext bytes without adding or normalising context entries. */
    fun generateDataKey(root: RootKey, context: EncryptionContext): Either<KeyOperationFailure, GeneratedKey>

    fun wrap(root: RootKey, plaintext: ByteArray, context: EncryptionContext): Either<KeyOperationFailure, ByteArray>

    fun unwrap(root: RootKey, wrapped: ByteArray, context: EncryptionContext): Either<KeyOperationFailure, ByteArray>
}

/** Discovery never creates, and eligibility belongs to the caller. */
interface RootOperations {
    fun describe(root: RootKey): Either<KeyOperationFailure, RootMetadata>

    /** All pages or a failure; this observation cannot prove a previously known root was destroyed. */
    fun listRoots(region: String): Either<KeyOperationFailure, RootListing>

    /** NotObserved means unresolved discovery, never proof that a prior resource was destroyed. */
    fun resolveAlias(region: String, alias: String): Either<KeyOperationFailure, RootMetadata>

    fun tags(root: RootKey): Either<KeyOperationFailure, KeyTags>

    /** Creates a multi-region primary with explicit policy; an uncertain reply never authorises another create. */
    fun createPrimary(request: RootCreation): Either<KeyOperationFailure, RootMetadata>

    /** A conflicting existing binding is refused; it is never reassigned. */
    fun bindAlias(root: RootKey, alias: String): Either<KeyOperationFailure, Unit>

    fun enableRotation(root: RootKey): Either<KeyOperationFailure, Unit>

    /** Creating metadata is pending, and an existing replica must be verified before success. */
    fun createReplica(primary: RootKey, request: RootCreation): Either<KeyOperationFailure, RootMetadata>

    fun inspectReplica(primary: RootKey, replica: RootKey): Either<KeyOperationFailure, RootMetadata>

    /**
     * Validate every expected location, relationship and required tag before any mutation; reject waits outside 7..30.
     * Schedule replicas before primary, retain aliases and preserve dates already accepted in either pending state.
     */
    fun scheduleDeletion(expected: ExpectedKeySet, waitingDays: Int): DeletionProgress
}

interface KeyOperations : DataKeyOperations, RootOperations
