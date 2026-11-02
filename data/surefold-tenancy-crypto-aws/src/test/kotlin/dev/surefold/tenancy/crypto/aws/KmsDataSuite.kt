package dev.surefold.tenancy.crypto.aws

import dev.surefold.Shared
import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import dev.surefold.tenancy.crypto.EncryptionContext
import dev.surefold.tenancy.crypto.KeyFailureCode
import dev.surefold.tenancy.crypto.KeyOperation
import dev.surefold.tenancy.crypto.KeyOperationFailure
import dev.surefold.tenancy.crypto.RootKey
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.KmsServiceClientConfiguration
import software.amazon.awssdk.services.kms.model.DataKeySpec
import software.amazon.awssdk.services.kms.model.DecryptRequest
import software.amazon.awssdk.services.kms.model.DecryptResponse
import software.amazon.awssdk.services.kms.model.DescribeKeyRequest
import software.amazon.awssdk.services.kms.model.DescribeKeyResponse
import software.amazon.awssdk.services.kms.model.EncryptRequest
import software.amazon.awssdk.services.kms.model.EncryptResponse
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse
import software.amazon.awssdk.services.kms.model.IncorrectKeyException
import software.amazon.awssdk.services.kms.model.InvalidCiphertextException
import software.amazon.awssdk.services.kms.model.KeyMetadata
import software.amazon.awssdk.services.kms.model.KmsInvalidStateException
import software.amazon.awssdk.services.kms.model.MultiRegionConfiguration
import software.amazon.awssdk.services.kms.model.MultiRegionKey
import software.amazon.awssdk.services.kms.model.NotFoundException
import java.util.concurrent.CancellationException

object KmsDataSuite : Suite() {
    init {
        test("generate requests AES 256 with the explicit root and exact legacy platform context") {
            val client = DataStub()
            val generated = operations(client).generateDataKey(RootKey(DATA_ARN), platformContext()).value()
            generated.plaintext.toList() shouldBe material().toList()
            generated.wrapped.toList() shouldBe ciphertext().toList()
            val request = client.requests.peek().single() as GenerateDataKeyRequest
            request.keyId() shouldBe DATA_ARN
            request.keySpec() shouldBe DataKeySpec.AES_256
            request.numberOfBytes() shouldBe null
            request.encryptionContext() shouldBe platformContext().entries
        }

        test("wrap and unwrap retain bytes and exact arbitrary context with explicit roots") {
            val client = DataStub()
            val operations = operations(client)
            val input = material()
            val wrapped = ciphertext()
            val context = EncryptionContext(mapOf("tenant_id" to "platform", "purpose" to "", "Version" to "01"))
            operations.wrap(RootKey(DATA_ARN), input, context).value().toList() shouldBe wrapped.toList()
            operations.unwrap(RootKey(DATA_ARN), wrapped, context).value().toList() shouldBe input.toList()
            input.toList() shouldBe material().toList()
            wrapped.toList() shouldBe ciphertext().toList()
            val encrypt = client.requests.peek()[0] as EncryptRequest
            encrypt.keyId() shouldBe DATA_ARN
            encrypt.encryptionContext() shouldBe context.entries
            val decrypt = client.requests.peek()[1] as DecryptRequest
            decrypt.keyId() shouldBe DATA_ARN
            decrypt.encryptionContext() shouldBe context.entries
            decrypt.ciphertextBlob().asByteArray().toList() shouldBe wrapped.toList()
        }

        test("empty contexts are preserved without defaults") {
            val client = DataStub()
            operations(client).generateDataKey(RootKey(DATA_ARN), EncryptionContext(emptyMap())).isRight() shouldBe true
            (client.requests.peek().single() as GenerateDataKeyRequest).encryptionContext() shouldBe emptyMap()
        }

        test("primary and replica reads require only the selected regional client") {
            for (region in listOf(DATA_REGION, READ_REGION)) {
                val root = RootKey(multiArn(DATA_REGION))
                val client = DataStub(region, responseArn = multiArn(region))
                val operations = KmsDataOperations(KmsObservations(mapOf(region to client)), region)
                operations.unwrap(root, ciphertext(), platformContext()).value().toList() shouldBe material().toList()
                root.id shouldBe multiArn(DATA_REGION)
                val requests = client.requests.peek()
                requests.filterIsInstance<DescribeKeyRequest>().map { it.keyId() } shouldBe
                    if (region == DATA_REGION) emptyList() else listOf(multiArn(READ_REGION))
                requests.filterIsInstance<DecryptRequest>().single().keyId() shouldBe multiArn(region)
            }
        }

        test("default data operations retain the supplied source Region") {
            val primary = DataStub(responseArn = multiArn(DATA_REGION))
            val replica = DataStub(READ_REGION, responseArn = multiArn(READ_REGION))
            val operations = KmsDataOperations(
                KmsObservations(mapOf(DATA_REGION to primary, READ_REGION to replica)),
            )
            operations.generateDataKey(RootKey(multiArn(DATA_REGION)), platformContext()).isRight() shouldBe true
            operations.wrap(RootKey(multiArn(DATA_REGION)), material(), platformContext()).isRight() shouldBe true
            primary.requests.peek().size shouldBe 2
            replica.requests.peek() shouldBe emptyList()
        }

        test("preferred data Region generates and wraps through only the verified local client") {
            val source = RootKey(multiArn(DATA_REGION))
            val plaintext = material()
            val context = EncryptionContext(mapOf("tenant_id" to "platform", "purpose" to "", "Version" to "01"))
            val local = DataStub(READ_REGION, responseArn = multiArn(READ_REGION), encrypt = { request ->
                request.plaintext().asByteArray().toList() shouldBe plaintext.toList()
                EncryptResponse.builder().keyId(multiArn(READ_REGION))
                    .ciphertextBlob(SdkBytes.fromByteArray(ciphertext())).build()
            })
            val operations = KmsDataOperations(KmsObservations(mapOf(READ_REGION to local)), READ_REGION)
            val generated = operations.generateDataKey(source, context).value()
            generated.plaintext.toList() shouldBe material().toList()
            generated.wrapped.toList() shouldBe ciphertext().toList()
            operations.wrap(source, plaintext, context).value().toList() shouldBe ciphertext().toList()
            source.id shouldBe multiArn(DATA_REGION)
            plaintext.toList() shouldBe material().toList()
            val requests = local.requests.peek()
            requests.filterIsInstance<DescribeKeyRequest>().map { it.keyId() } shouldBe
                List(2) { multiArn(READ_REGION) }
            val generate = requests.filterIsInstance<GenerateDataKeyRequest>().single()
            generate.keyId() shouldBe multiArn(READ_REGION)
            generate.keySpec() shouldBe DataKeySpec.AES_256
            generate.encryptionContext() shouldBe context.entries
            val encrypt = requests.filterIsInstance<EncryptRequest>().single()
            encrypt.keyId() shouldBe multiArn(READ_REGION)
            encrypt.encryptionContext() shouldBe context.entries
        }

        test("locally routed generation and wrapping require the returned local identity") {
            for ((arn, code) in listOf(
                multiArn(DATA_REGION) to KeyFailureCode.Conflict,
                null to KeyFailureCode.IncompleteEvidence,
            )) {
                val plaintext = material()
                val generated = GenerateDataKeyResponse.builder().keyId(arn)
                    .plaintext(SdkBytes.fromByteArrayUnsafe(plaintext))
                    .ciphertextBlob(SdkBytes.fromByteArray(ciphertext())).build()
                val local = DataStub(READ_REGION, responseArn = arn, generate = { generated })
                val operations = KmsDataOperations(KmsObservations(mapOf(READ_REGION to local)), READ_REGION)
                operations.generateDataKey(RootKey(multiArn(DATA_REGION)), platformContext()).failure() shouldBe
                    KeyOperationFailure(KeyOperation.Generate, code)
                plaintext.all { it == 0.toByte() } shouldBe true
                val input = material()
                operations.wrap(RootKey(multiArn(DATA_REGION)), input, platformContext()).failure() shouldBe
                    KeyOperationFailure(KeyOperation.Wrap, code)
                input.toList() shouldBe material().toList()
            }
        }

        test("local metadata must observe both the stored source and selected candidate") {
            val unrelated = localMetadata().toBuilder().multiRegionConfiguration(
                MultiRegionConfiguration.builder().multiRegionKeyType("REPLICA")
                    .primaryKey(dataRelation("eu-central-1")).replicaKeys(dataRelation(READ_REGION)).build(),
            ).build()
            for ((metadata, code) in listOf(
                null to KeyFailureCode.IncompleteEvidence,
                unrelated to KeyFailureCode.Conflict,
                localMetadata().toBuilder().keyId("mrk-${"b".repeat(32)}").build() to KeyFailureCode.Conflict,
                localMetadata().toBuilder().awsAccountId("999999999999").build() to KeyFailureCode.Conflict,
                localMetadata().toBuilder().arn(multiArn(DATA_REGION)).build() to KeyFailureCode.Conflict,
                localMetadata().toBuilder().multiRegionConfiguration(null as MultiRegionConfiguration?).build() to
                    KeyFailureCode.IncompleteEvidence,
            )) {
                val local = DataStub(READ_REGION, responseArn = multiArn(READ_REGION), metadata = metadata)
                val operations = KmsDataOperations(KmsObservations(mapOf(READ_REGION to local)), READ_REGION)
                for ((operation, result) in dataResults(operations)) {
                    result.failure() shouldBe KeyOperationFailure(operation, code)
                }
                local.requests.peek().size shouldBe 3
                local.requests.peek().all { it is DescribeKeyRequest } shouldBe true
            }
        }

        test("local absence or unavailability fails closed without another regional call") {
            for (failure in listOf(
                NotFoundException.builder().message("secret context").build(),
                SdkClientException.create("secret context"),
            )) {
                val local = DataStub(READ_REGION, describe = { throw failure })
                val operations = KmsDataOperations(KmsObservations(mapOf(READ_REGION to local)), READ_REGION)
                for ((operation, result) in dataResults(operations)) {
                    result.failure().operation shouldBe operation
                    result.failure().code shouldBe if (failure is NotFoundException) {
                        KeyFailureCode.NotObserved
                    } else KeyFailureCode.Unavailable
                }
                local.requests.peek().size shouldBe 3
            }
            for ((operation, result) in dataResults(KmsDataOperations(KmsObservations(emptyMap()), READ_REGION))) {
                result.failure() shouldBe KeyOperationFailure(operation, KeyFailureCode.Unavailable)
            }
            val ordinary = DataStub(READ_REGION)
            KmsDataOperations(KmsObservations(mapOf(READ_REGION to ordinary)), READ_REGION)
                .unwrap(RootKey(DATA_ARN), ciphertext(), platformContext()).isLeft() shouldBe true
            ordinary.requests.peek() shouldBe emptyList()
        }

        test("local transient states remain retryable while disabled and pending states refuse data operations") {
            for ((state, code) in listOf(
                "Creating" to KeyFailureCode.Unavailable, "Updating" to KeyFailureCode.Unavailable,
                "Unavailable" to KeyFailureCode.Unavailable, "future" to KeyFailureCode.Unavailable,
                "Disabled" to KeyFailureCode.Refused, "PendingDeletion" to KeyFailureCode.Refused,
                "PendingReplicaDeletion" to KeyFailureCode.Refused,
            )) {
                val local = DataStub(READ_REGION, metadata = localMetadata().toBuilder().keyState(state).build())
                val operations = KmsDataOperations(KmsObservations(mapOf(READ_REGION to local)), READ_REGION)
                for ((operation, result) in dataResults(operations)) {
                    result.failure() shouldBe KeyOperationFailure(operation, code)
                }
                local.requests.peek().size shouldBe 3
            }
        }

        test("same region invalid state replies consult verified state and retain operation semantics") {
            for ((state, code) in listOf(
                "Creating" to KeyFailureCode.Unavailable, "Updating" to KeyFailureCode.Unavailable,
                "Unavailable" to KeyFailureCode.Unavailable, "future" to KeyFailureCode.Unavailable,
                "Disabled" to KeyFailureCode.Refused, "PendingDeletion" to KeyFailureCode.Refused,
                "PendingReplicaDeletion" to KeyFailureCode.Refused, "Enabled" to KeyFailureCode.Refused,
            )) {
                val metadata = KeyMetadata.builder().arn(DATA_ARN).keyId(DATA_UUID).awsAccountId(DATA_ACCOUNT)
                    .keyState(state).multiRegion(false).build()
                val refused = KmsInvalidStateException.builder().message("private state").build()
                val client = DataStub(
                    metadata = metadata, generate = { throw refused },
                    encrypt = { throw refused }, decrypt = { throw refused },
                )
                val operations = operations(client)
                operations.generateDataKey(RootKey(DATA_ARN), platformContext()).failure() shouldBe
                    KeyOperationFailure(KeyOperation.Generate, code)
                operations.wrap(RootKey(DATA_ARN), material(), platformContext()).failure() shouldBe
                    KeyOperationFailure(KeyOperation.Wrap, code)
                operations.unwrap(RootKey(DATA_ARN), ciphertext(), platformContext()).failure() shouldBe
                    KeyOperationFailure(KeyOperation.Unwrap, code)
                client.requests.peek().filterIsInstance<DescribeKeyRequest>().map { it.keyId() } shouldBe
                    List(3) { DATA_ARN }
            }
        }

        test("malformed generated material is rejected and untransferred plaintext is erased") {
            for ((plain, wrapped) in listOf(
                null to ciphertext(), byteArrayOf() to ciphertext(), ByteArray(31) { 4 } to ciphertext(),
                material() to null, material() to byteArrayOf(),
            )) {
                val generated = GenerateDataKeyResponse.builder().keyId(DATA_ARN)
                    .plaintext(plain?.let(SdkBytes::fromByteArrayUnsafe))
                    .ciphertextBlob(wrapped?.let(SdkBytes::fromByteArray)).build()
                operations(DataStub(generate = { generated })).generateDataKey(RootKey(DATA_ARN), platformContext())
                    .failure().code shouldBe KeyFailureCode.IncompleteEvidence
                plain?.all { it == 0.toByte() } shouldBe (plain?.let { true })
            }
        }

        test("every data response verifies full root identity before transferring material") {
            for ((arn, code) in listOf(
                null to KeyFailureCode.IncompleteEvidence, "alias/root" to KeyFailureCode.IncompleteEvidence,
                DATA_ARN.replace(DATA_ACCOUNT, "999999999999") to KeyFailureCode.Conflict,
                DATA_ARN.replace(DATA_REGION, READ_REGION) to KeyFailureCode.Conflict,
                DATA_ARN.replace(DATA_UUID, "12345678-1234-1234-1234-123456789abd") to KeyFailureCode.Conflict,
            )) {
                val plain = material()
                val generated = GenerateDataKeyResponse.builder().keyId(arn)
                    .plaintext(SdkBytes.fromByteArrayUnsafe(plain))
                    .ciphertextBlob(SdkBytes.fromByteArray(ciphertext())).build()
                val client = DataStub(responseArn = arn, generate = { generated })
                operations(client).generateDataKey(RootKey(DATA_ARN), platformContext()).failure().code shouldBe code
                plain.all { it == 0.toByte() } shouldBe true
                operations(client).wrap(RootKey(DATA_ARN), material(), platformContext()).failure().code shouldBe code
                val decrypted = material()
                val decrypting = DataStub(decrypt = {
                    DecryptResponse.builder().keyId(arn).plaintext(SdkBytes.fromByteArrayUnsafe(decrypted)).build()
                })
                operations(decrypting).unwrap(RootKey(DATA_ARN), ciphertext(), platformContext())
                    .failure().code shouldBe
                    code
                decrypted.all { it == 0.toByte() } shouldBe true
            }
        }

        test("missing and empty wrap or decrypt bytes cannot succeed") {
            for (bytes in listOf(null, byteArrayOf())) {
                val client = DataStub(encrypt = {
                    EncryptResponse.builder().keyId(DATA_ARN)
                        .ciphertextBlob(bytes?.let(SdkBytes::fromByteArray)).build()
                }, decrypt = {
                    DecryptResponse.builder().keyId(DATA_ARN).plaintext(bytes?.let(SdkBytes::fromByteArray)).build()
                })
                operations(client).wrap(RootKey(DATA_ARN), material(), platformContext()).failure().code shouldBe
                    KeyFailureCode.IncompleteEvidence
                operations(client).unwrap(RootKey(DATA_ARN), ciphertext(), platformContext()).failure().code shouldBe
                    KeyFailureCode.IncompleteEvidence
            }
        }

        test("provider wrong root and context failures stay redacted and caller arrays survive") {
            for (failure in listOf(
                IncorrectKeyException.builder().message("private root").build(),
                InvalidCiphertextException.builder().message("private context").build(),
            )) {
                val client = DataStub(
                    encrypt = { throw failure }, decrypt = { throw failure }, generate = { throw failure },
                )
                val plaintext = material()
                val wrapped = ciphertext()
                operations(client).generateDataKey(RootKey(DATA_ARN), platformContext()).failure() shouldBe
                    KeyOperationFailure(KeyOperation.Generate, KeyFailureCode.InvalidCiphertext)
                operations(client).wrap(RootKey(DATA_ARN), plaintext, platformContext()).failure() shouldBe
                    KeyOperationFailure(KeyOperation.Wrap, KeyFailureCode.InvalidCiphertext)
                operations(client).unwrap(RootKey(DATA_ARN), wrapped, platformContext()).failure() shouldBe
                    KeyOperationFailure(KeyOperation.Unwrap, KeyFailureCode.InvalidCiphertext)
                plaintext.toList() shouldBe material().toList()
                wrapped.toList() shouldBe ciphertext().toList()
                val request = client.requests.peek().filterIsInstance<EncryptRequest>().single()
                request.plaintext().asByteArrayUnsafe().all { it == 0.toByte() } shouldBe true
            }
        }

        test("invalid roots and missing clients fail before SDK calls and programmer errors propagate") {
            val client = DataStub()
            for (root in listOf(RootKey("alias/root"), RootKey("private"))) {
                operations(client).generateDataKey(root, platformContext()).failure().code shouldBe
                    KeyFailureCode.InvalidInput
                operations(client).wrap(root, material(), platformContext()).failure().code shouldBe
                    KeyFailureCode.InvalidInput
                operations(client).unwrap(root, ciphertext(), platformContext()).failure().code shouldBe
                    KeyFailureCode.InvalidInput
            }
            client.requests.peek() shouldBe emptyList()
            operations(client).unwrap(
                RootKey(DATA_ARN.replace(DATA_REGION, READ_REGION)), ciphertext(), platformContext(),
            )
                .failure().code shouldBe
                KeyFailureCode.Unavailable
            for (error in listOf(IllegalStateException("bug"), CancellationException("cancelled"))) {
                val broken = DataStub(generate = { throw error })
                shouldThrow<RuntimeException> {
                    operations(broken).generateDataKey(RootKey(DATA_ARN), platformContext())
                }
            }
        }
    }
}

private const val DATA_REGION = "eu-west-2"
private const val READ_REGION = "eu-west-1"
private const val DATA_ACCOUNT = "123456789012"
private const val DATA_UUID = "12345678-1234-1234-1234-123456789abc"
private const val DATA_ARN = "arn:aws:kms:$DATA_REGION:$DATA_ACCOUNT:key/$DATA_UUID"
private val DATA_MRK = "mrk-${"a".repeat(32)}"
private fun multiArn(region: String) = "arn:aws:kms:$region:$DATA_ACCOUNT:key/$DATA_MRK"
private fun material() = ByteArray(32) { (it + 1).toByte() }
private fun ciphertext() = byteArrayOf(9, 8, 7)
private fun platformContext() = EncryptionContext(
    mapOf("tenant_id" to "platform", "purpose" to "enc", "version" to "1"),
)
private fun dataRelation(region: String) = MultiRegionKey.builder().arn(multiArn(region)).region(region).build()
private fun localMetadata() = KeyMetadata.builder().arn(multiArn(READ_REGION)).keyId(DATA_MRK)
    .awsAccountId(DATA_ACCOUNT).keyState("Enabled").multiRegion(true).multiRegionConfiguration(
        MultiRegionConfiguration.builder().multiRegionKeyType("REPLICA").primaryKey(dataRelation(DATA_REGION))
            .replicaKeys(dataRelation(READ_REGION)).build(),
    ).build()
private fun dataResults(operations: KmsDataOperations): List<Pair<KeyOperation, Either<KeyOperationFailure, *>>> {
    val root = RootKey(multiArn(DATA_REGION))
    val plaintext = material()
    val results = listOf(
        KeyOperation.Generate to operations.generateDataKey(root, platformContext()),
        KeyOperation.Wrap to operations.wrap(root, plaintext, platformContext()),
        KeyOperation.Unwrap to operations.unwrap(root, ciphertext(), platformContext()),
    )
    root.id shouldBe multiArn(DATA_REGION)
    plaintext.toList() shouldBe material().toList()
    return results
}
private fun operations(client: DataStub) = KmsDataOperations(KmsObservations(mapOf(DATA_REGION to client)))
private fun <A> Either<KeyOperationFailure, A>.value(): A = (this as Either.Right).value
private fun <A> Either<KeyOperationFailure, A>.failure(): KeyOperationFailure = (this as Either.Left).value

private class DataStub(
    private val region: String = DATA_REGION,
    private val responseArn: String? = DATA_ARN,
    private val metadata: KeyMetadata? = localMetadata(),
    private val describe: (() -> DescribeKeyResponse)? = null,
    private val generate: (() -> GenerateDataKeyResponse)? = null,
    private val encrypt: ((EncryptRequest) -> EncryptResponse)? = null,
    private val decrypt: (() -> DecryptResponse)? = null,
) : KmsClient {
    val requests = Shared(emptyList<Any>())
    override fun serviceName() = "kms"
    override fun close() = Unit
    override fun serviceClientConfiguration(): KmsServiceClientConfiguration =
        KmsServiceClientConfiguration.builder().region(Region.of(region)).build()
    override fun describeKey(request: DescribeKeyRequest): DescribeKeyResponse {
        requests.update { it + request }
        return describe?.invoke() ?: DescribeKeyResponse.builder().keyMetadata(metadata).build()
    }
    override fun generateDataKey(request: GenerateDataKeyRequest): GenerateDataKeyResponse {
        requests.update { it + request }
        return generate?.invoke() ?: GenerateDataKeyResponse.builder().keyId(responseArn)
            .plaintext(SdkBytes.fromByteArray(material())).ciphertextBlob(SdkBytes.fromByteArray(ciphertext())).build()
    }
    override fun encrypt(request: EncryptRequest): EncryptResponse {
        requests.update { it + request }
        return encrypt?.invoke(request) ?: EncryptResponse.builder().keyId(responseArn)
            .ciphertextBlob(SdkBytes.fromByteArray(ciphertext())).build()
    }
    override fun decrypt(request: DecryptRequest): DecryptResponse {
        requests.update { it + request }
        return decrypt?.invoke() ?: DecryptResponse.builder().keyId(responseArn)
            .plaintext(SdkBytes.fromByteArray(material())).build()
    }
}
