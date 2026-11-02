package io.github.matthewjones372.surefold.tenancy.crypto.aws

import dev.surefold.fail.Either
import dev.surefold.use
import io.github.matthewjones372.surefold.tenancy.crypto.EncryptionContext
import io.github.matthewjones372.surefold.tenancy.crypto.ExpectedKeySet
import io.github.matthewjones372.surefold.tenancy.crypto.KeyFailureCode
import io.github.matthewjones372.surefold.tenancy.crypto.KeyOperation
import io.github.matthewjones372.surefold.tenancy.crypto.KeyOperationFailure
import io.github.matthewjones372.surefold.tenancy.crypto.KeyOperations
import io.github.matthewjones372.surefold.tenancy.crypto.RootCreation
import io.github.matthewjones372.surefold.tenancy.crypto.RootExpectation
import io.github.matthewjones372.surefold.tenancy.crypto.RootKey
import io.github.matthewjones372.surefold.tenancy.crypto.RootMetadata
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.exception.SdkClientException
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.readText

/** Manual disposable-root proof only; each invocation makes one stage and never polls or repeats uncertain work. */
object KmsRegionalProof {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            proof(args.toList())
        } catch (stopped: ProofStopped) {
            error(stopped.message ?: "proof incomplete-evidence")
        } catch (_: SdkClientException) {
            error("proof unavailable")
        } catch (_: IOException) {
            error("proof unavailable")
        }
    }
}

private fun proof(args: List<String>) {
    val input = proofInput(args)
    DefaultCredentialsProvider.builder().build().use { provider ->
        awsKeyOperations(input.regions, provider, input.preferred) use { operations ->
            if (input.stage == "create-primary") {
                show(operations.createPrimary(input.creation(input.regions.first())).proved())
            } else {
                operations.rootStage(input)
            }
        }
    }
}

private fun proofInput(args: List<String>): ProofInput {
    val options = args.filter { it != "--mutate" }.map { argument ->
        checkProof(argument.startsWith("--") && argument.contains('='))
        argument.removePrefix("--").substringBefore('=') to argument.substringAfter('=')
    }
    checkProof(options.map { it.first }.distinct().size == options.size)
    val input = ProofInput(options.toMap())
    checkProof(input.values.keys.all { it in proofOptions })
    checkProof(input.stage in proofStages)
    checkProof(input.stage in setOf("inspect", "observe", "crypto") || "--mutate" in args)
    checkProof(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(input.required("proof")))
    checkProof(input.regions.all { Regex("[a-z]{2}(-[a-z]+)+-[0-9]+").matches(it) })
    checkProof(input.stage == "create-primary" || input.root != null)
    return input
}

private class ProofInput(val values: Map<String, String>) {
    val stage = required("stage")
    val regions = required("regions").split(',').toSet()
    val tags = mapOf("surefold:proof" to required("proof"), "surefold:disposable" to "true")
    val preferred = values["data-region"]
    val root = values["root"]?.let(::RootKey)
    fun required(name: String) = values[name]?.takeIf { it.isNotBlank() } ?: throw ProofStopped("proof invalid-input")
    fun creation(region: String) = RootCreation(
        region, Path.of(required("policy")).readText(), "surefold-disposable-proof", tags,
    )
    fun localRoot(original: RootKey): RootKey {
        if (stage != "crypto" || preferred == null) return original
        val identity = KmsIdentity.parse(original, KeyOperation.Describe).proved()
        val location = identity.location
        return RootKey("arn:${location.partition}:kms:$preferred:${location.account}:key/${identity.keyId}")
    }
}

private fun KeyOperations.rootStage(input: ProofInput) {
    val root = input.root ?: throw ProofStopped("proof invalid-input")
    if (input.stage == "observe") {
        observeRecordedRoots(this, listOf(root, RootKey(input.required("replica-root"))), input.tags, ::println)
        return
    }
    val observed = checked(input.localRoot(root), input.tags)
    when (input.stage) {
        "inspect" -> {
            show(observed)
            for (replica in observed.replicas) {
                checked(replica.root, input.tags)
                show(inspectReplica(root, replica.root).proved())
            }
        }

        "prepare" -> {
            bindAlias(root, input.required("alias")).proved()
            enableRotation(root).proved()
            show(observed)
        }

        "replicate" -> show(createReplica(root, input.creation(input.required("replica-region"))).proved())

        "crypto" -> cryptoProof(this, root)

        "delete" -> deleteProof(observed, input.tags)
    }
}

private fun KeyOperations.deleteProof(observed: RootMetadata, tags: Map<String, String>) {
    checkProof(observed.primary == observed.location)
    val replicas = observed.replicas.map { checked(it.root, tags) }
    val progress = scheduleDeletion(
        ExpectedKeySet(
            RootExpectation(observed.location, tags),
            replicas.map { RootExpectation(it.location, tags) },
        ),
        7,
    )
    progress.regions.forEach { println("${it.location.root.id} ${it.state} ${it.effectiveAt ?: "unknown"}") }
    progress.failure?.let { throw ProofStopped("${it.operation} ${it.code}") }
}

private fun cryptoProof(operations: KeyOperations, root: RootKey) {
    val exact = EncryptionContext(mapOf("tenant_id" to "platform", "purpose" to "enc", "version" to "1"))
    val generated = operations.generateDataKey(root, exact).proved()
    try {
        val direct = operations.unwrap(root, generated.wrapped, exact).proved()
        try {
            checkProof(direct.contentEquals(generated.plaintext))
        } finally {
            direct.fill(0)
        }
        val wrapped = operations.wrap(root, generated.plaintext, exact).proved()
        val plaintext = operations.unwrap(root, wrapped, exact).proved()
        try {
            checkProof(plaintext.contentEquals(generated.plaintext))
            val changed = EncryptionContext(exact.entries + ("version" to "2"))
            val refused = operations.unwrap(root, wrapped, changed)
            checkProof(
                refused is Either.Left &&
                    refused.value.code in setOf(KeyFailureCode.InvalidCiphertext, KeyFailureCode.Refused),
            )
            println("${root.id} crypto-verified")
        } finally {
            plaintext.fill(0)
        }
    } finally {
        generated.plaintext.fill(0)
    }
}

private fun KeyOperations.checked(root: RootKey, tags: Map<String, String>): RootMetadata {
    val metadata = describe(root).proved()
    val actual = tags(root).proved().entries
    checkProof(tags.all { (key, value) -> actual[key] == value })
    return metadata
}
private fun show(metadata: RootMetadata) = println("${metadata.location.root.id} ${metadata.state}")
private fun checkProof(accepted: Boolean) {
    if (!accepted) throw ProofStopped("proof invalid-input-or-evidence")
}
private fun <A> Either<KeyOperationFailure, A>.proved(): A = when (this) {
    is Either.Right -> value
    is Either.Left -> throw ProofStopped("${value.operation} ${value.code}")
}
private class ProofStopped(message: String) : IllegalStateException(message)
private val proofStages = setOf("create-primary", "prepare", "replicate", "inspect", "observe", "crypto", "delete")
private val proofOptions =
    setOf("stage", "proof", "regions", "root", "policy", "alias", "replica-region", "replica-root", "data-region")

internal fun observeRecordedRoots(
    operations: KeyOperations,
    roots: List<RootKey>,
    requiredTags: Map<String, String>,
    emit: (String) -> Unit,
) {
    roots.forEach { KmsIdentity.parse(it, KeyOperation.Describe).proved() }
    val complete = roots.map { root ->
        when (val observed = operations.describe(root)) {
            is Either.Left -> {
                emit("${root.id} ${observed.value.operation} ${observed.value.code}")
                false
            }

            is Either.Right -> {
                emit("${root.id} ${observed.value.state}")
                proofTagsMatch(operations, root, requiredTags, emit)
            }
        }
    }
    if (complete.any { !it }) throw ProofStopped("proof incomplete-evidence")
}

private fun proofTagsMatch(
    operations: KeyOperations,
    root: RootKey,
    required: Map<String, String>,
    emit: (String) -> Unit,
): Boolean = when (val tags = operations.tags(root)) {
    is Either.Left -> {
        emit("${root.id} ${tags.value.operation} ${tags.value.code}")
        false
    }

    is Either.Right -> {
        val matched = required.all { (key, value) -> tags.value.entries[key] == value }
        if (!matched) emit("${root.id} Tags Conflict")
        matched
    }
}
