package dev.surefold.tenancy.crypto.aws

import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldThrow
import dev.surefold.tenancy.TenantId
import dev.surefold.tenancy.crypto.KeyContext
import dev.surefold.tenancy.crypto.KeyError
import dev.surefold.tenancy.crypto.KeyOwner
import dev.surefold.tenancy.crypto.KeyPurpose
import org.testcontainers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kms.KmsClient
import java.util.UUID
import kotlin.time.Duration.Companion.days

/** A root per tenant on LocalStack's KMS, an unwrap held to its context, and a deletion denied after (spec 0235). */
object AwsKmsSuite : Suite() {

    private val localstack: LocalStackContainer by lazy {
        LocalStackContainer(
            DockerImageName.parse("public.ecr.aws/localstack/localstack:4.4")
                .asCompatibleSubstituteFor("localstack/localstack"),
        ).withServices("kms").apply { start() }
    }

    private val client: KmsClient by lazy {
        KmsClient.builder()
            .endpointOverride(localstack.endpoint)
            .region(Region.of(localstack.region))
            .credentialsProvider(StaticCredentialsProvider.create(credentials()))
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build()
    }

    private fun credentials() = AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey)

    /** A KMS under an alias prefix of its own, so tests share LocalStack and none of each other's keys. */
    private fun kms() = AwsKms(client, "alias/surefold-test/${UUID.randomUUID()}/")

    private fun <A> Either<KeyError, A>.held(): A = (this as Either.Right).value

    private val acme = KeyOwner.Tenant(TenantId("acme"))
    private val globex = KeyOwner.Tenant(TenantId("globex"))

    init {
        test("provision finds the tenant's root by alias, and a key unwraps under its own context only") {
            val kms = kms()
            val root = kms.createRoot(acme).held()
            kms.createRoot(acme).held() shouldBe root
            (kms.createRoot(globex).held() == root) shouldBe false

            val context = KeyContext(acme, KeyPurpose.Enc, 1)
            val key = kms.generateDataKey(root, context).held()

            kms.decrypt(root, key.wrapped, context).held().toList() shouldBe key.plaintext.toList()
            kms.decrypt(root, key.wrapped, KeyContext(globex, KeyPurpose.Enc, 1))
                .shouldBeInstanceOf<Either.Left<KeyError.Invalid>>()
        }

        test("a root scheduled for deletion is denied, and its alias goes so a new provision makes a new root") {
            val kms = kms()
            val root = kms.createRoot(acme).held()
            val context = KeyContext(acme, KeyPurpose.Enc, 1)
            val key = kms.generateDataKey(root, context).held()

            kms.scheduleDeletion(root, waiting = 30.days).held()

            kms.decrypt(root, key.wrapped, context).shouldBeInstanceOf<Either.Left<KeyError.Denied>>()
            (kms.createRoot(acme).held() == root) shouldBe false
        }

        test("the platform has a root of its own, and a tenant id that cannot name an alias is refused") {
            val kms = kms()
            kms.createRoot(KeyOwner.Platform).shouldBeInstanceOf<Either.Right<*>>()
            shouldThrow<IllegalArgumentException> { kms.createRoot(KeyOwner.Tenant(TenantId("acme corp"))) }
        }
    }
}
