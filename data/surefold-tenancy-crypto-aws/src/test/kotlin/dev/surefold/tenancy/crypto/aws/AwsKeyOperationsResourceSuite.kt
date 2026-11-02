package dev.surefold.tenancy.crypto.aws

import dev.surefold.Resource
import dev.surefold.Shared
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import dev.surefold.resourceScope
import dev.surefold.tenancy.crypto.KeyOperations
import dev.surefold.tenancy.crypto.RootKey
import dev.surefold.use
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.HttpExecuteResponse
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpResponse
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity
import software.amazon.awssdk.identity.spi.ResolveIdentityRequest
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.KmsClientBuilder
import java.io.IOException
import java.util.concurrent.CompletableFuture

object AwsKeyOperationsResourceSuite : Suite() {
    init {
        test("the public Resource compiles against the neutral port and retains the caller provider") {
            val provider = RefreshingProvider()
            val clients: Resource<AwsKeyOperations> = awsKeyOperations(setOf("eu-west-2"), provider)
            clients use { adapter ->
                val operations: KeyOperations = adapter
                (
                    operations.describe(RootKey("invalid")) is
                        dev.surefold.fail.Either.Left
                    ) shouldBe true
            }
            provider.closed.peek() shouldBe false
            provider.resolved.peek() shouldBe 0
        }
        test("owned clients use separate bounded normal and single attempt creation clients per Region") {
            val provider = RefreshingProvider()
            val acquired = Shared(emptyList<KmsClient>())
            val closed = Shared(emptyList<Int>())
            ownedForTest(linkedSetOf("eu-west-2", "eu-west-1"), provider) { builder ->
                val client = builder.build()
                val index = acquired.peek().size
                acquired.update { it + client }
                RecordingClient(client, index, closed)
            } use { _ ->
                acquired.peek().map { it.serviceClientConfiguration().region().id() } shouldBe
                    listOf("eu-west-2", "eu-west-2", "eu-west-1", "eu-west-1")
                acquired.peek().map {
                    it.serviceClientConfiguration().overrideConfiguration().retryStrategy().get().maxAttempts()
                } shouldBe listOf(3, 1, 3, 1)
                (acquired.peek()[0] === acquired.peek()[1]) shouldBe false
                closed.peek() shouldBe emptyList()
            }
            closed.peek() shouldBe listOf(3, 2, 1, 0)
            provider.closed.peek() shouldBe false
        }

        test("partial acquisition rolls back every earlier client in reverse order without closing the provider") {
            for (failAt in 0..3) {
                val provider = RefreshingProvider()
                val calls = Shared(0)
                val closed = Shared(emptyList<Int>())
                val failure = IllegalStateException("build failed")
                shouldThrow<IllegalStateException>({
                    ownedForTest(linkedSetOf("eu-west-2", "eu-west-1"), provider) { builder ->
                        val index = calls.peek()
                        calls.update { it + 1 }
                        if (index == failAt) throw failure
                        RecordingClient(builder.build(), index, closed)
                    } use { throw AssertionError("acquisition must fail") }
                }) { it shouldBe failure }
                closed.peek() shouldBe (0 until failAt).reversed().toList()
                provider.closed.peek() shouldBe false
            }
        }

        test("every release runs when multiple client closes fail and acquisition failures remain primary") {
            for (failBuild in listOf(false, true)) {
                val closed = Shared(emptyList<Int>())
                val calls = Shared(0)
                val provider = RefreshingProvider()
                val failure = IllegalStateException("build failed")
                val resource = ownedForTest(linkedSetOf("eu-west-2", "eu-west-1"), provider) { builder ->
                    val index = calls.peek()
                    calls.update { it + 1 }
                    if (failBuild && index == 3) throw failure
                    RecordingClient(builder.build(), index, closed, failClose = true)
                }
                if (failBuild) {
                    shouldThrow<IllegalStateException>({ resource use { Unit } }) {
                        it shouldBe failure
                        it.suppressed.size shouldBe 3
                    }
                    closed.peek() shouldBe listOf(2, 1, 0)
                } else {
                    shouldThrow<IOException>({ resource use { Unit } }) { it.suppressed.size shouldBe 3 }
                    closed.peek() shouldBe listOf(3, 2, 1, 0)
                }
                provider.closed.peek() shouldBe false
            }
        }

        test("SDK normal retries reach the wire and refreshed credentials remain caller owned") {
            val baseline = RefreshingProvider()
            KmsClient.builder().region(software.amazon.awssdk.regions.Region.EU_WEST_2)
                .credentialsProvider(baseline).httpClient(ResourceTransport()).build().use { client ->
                    client.serviceClientConfiguration().credentialsProvider()
                        .resolveIdentity(ResolveIdentityRequest.builder().build()).join()
                }
            baseline.identities.peek() shouldBe 1
            val provider = RefreshingProvider()
            val transport = ResourceTransport()
            val clients = Shared(emptyList<KmsClient>())
            ownedForTest(setOf("eu-west-2"), provider) { builder ->
                val client = builder.httpClientBuilder(null).httpClient(transport).build()
                clients.update { it + client }
                client
            } use { operations ->
                (operations.listRoots("eu-west-2") is dev.surefold.fail.Either.Right) shouldBe
                    true
                transport.calls.peek() shouldBe 3
                provider.version.update { 2 }
                (operations.listRoots("eu-west-2") is dev.surefold.fail.Either.Right) shouldBe
                    true
                transport.calls.peek() shouldBe 4
                transport.identities.peek().last() shouldBe true
                provider.resolved.peek() shouldBe 2
                provider.identities.peek() shouldBe 2
                val identity = clients.peek().first().serviceClientConfiguration().credentialsProvider()
                    .resolveIdentity(ResolveIdentityRequest.builder().build()).join()
                identity.accessKeyId() shouldBe "test-key-2"
                provider.identities.peek() shouldBe 3
                provider.resolved.peek() shouldBe 3
            }
            provider.closed.peek() shouldBe false
            provider.resolveCredentials().accessKeyId() shouldBe "test-key-2"
            clients.peek().size shouldBe 2
        }

        test("the Resource snapshots requested Regions before acquisition") {
            val regions = linkedSetOf("eu-west-2")
            val built = Shared(emptyList<String>())
            val resource = ownedForTest(regions, RefreshingProvider()) { builder ->
                builder.build().also { client ->
                    built.update { it + client.serviceClientConfiguration().region().id() }
                }
            }
            regions.clear()
            resourceScope { resource.bind() }
            built.peek() shouldBe listOf("eu-west-2", "eu-west-2")
        }
    }
}

private fun ownedForTest(
    regions: Set<String>,
    provider: AwsCredentialsProvider,
    build: (KmsClientBuilder) -> KmsClient,
): Resource<AwsKeyOperations> = ownedKeyOperations(regions, provider, null, build)

private class RefreshingProvider : AwsCredentialsProvider, AutoCloseable {
    val closed = Shared(false)
    val version = Shared(1)
    val resolved = Shared(0)
    val identities = Shared(0)
    override fun resolveIdentity(request: ResolveIdentityRequest): CompletableFuture<AwsCredentialsIdentity> {
        identities.update { count -> count + 1 }
        return super<AwsCredentialsProvider>.resolveIdentity(request)
    }
    override fun resolveCredentials(): AwsCredentials {
        resolved.update { it + 1 }
        return AwsBasicCredentials.create("test-key-${version.peek()}", "test-secret")
    }
    override fun close() { closed.update { true } }
}

private class RecordingClient(
    private val client: KmsClient,
    private val index: Int,
    private val closed: Shared<List<Int>>,
    private val failClose: Boolean = false,
) : KmsClient by client {
    override fun close() {
        closed.update { it + index }
        client.close()
        if (failClose) throw IOException("close failed")
    }
}

private class ResourceTransport : SdkHttpClient {
    val calls = Shared(0)
    val identities = Shared(emptyList<Boolean>())
    override fun clientName() = "resource-test"
    override fun close() = Unit
    override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest = object : ExecutableHttpRequest {
        override fun abort() = Unit
        override fun call(): HttpExecuteResponse {
            val index = calls.peek()
            calls.update { it + 1 }
            identities.update {
                it + request.httpRequest().firstMatchingHeader("Authorization").orElse("")
                    .contains("Credential=test-key-2/")
            }
            val body = if (index < 2) "{\"__type\":\"KMSInternalException\"}" else "{\"Keys\":[],\"Truncated\":false}"
            return HttpExecuteResponse.builder().response(
                SdkHttpResponse.builder().statusCode(if (index < 2) 500 else 200).build(),
            ).responseBody(AbortableInputStream.create(body.byteInputStream())).build()
        }
    }
}
