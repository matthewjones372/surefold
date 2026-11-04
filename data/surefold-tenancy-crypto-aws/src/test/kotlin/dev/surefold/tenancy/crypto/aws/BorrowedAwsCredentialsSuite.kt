package dev.surefold.tenancy.crypto.aws

import dev.surefold.Shared
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity
import software.amazon.awssdk.identity.spi.ResolveIdentityRequest
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kms.KmsClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

object BorrowedAwsCredentialsSuite : Suite() {
    init {
        test("closing SDK clients leaves their caller owned refreshing provider open") {
            val owner = CallerCredentials()
            val configured = borrowedAwsCredentialsProvider(owner)
            KmsClient.builder().region(Region.EU_WEST_2).credentialsProvider(configured)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build().use { _ ->
                    owner.resolved.peek() shouldBe 0
                }
            owner.closed.peek() shouldBe false
            owner.close()
            owner.closed.peek() shouldBe true
        }
        test("the borrowed view is lazy and forwards each refreshed credential unchanged") {
            val owner = CallerCredentials()
            val borrowed = borrowedAwsCredentialsProvider(owner)
            (borrowed is AutoCloseable) shouldBe false
            owner.resolved.peek() shouldBe 0
            owner.requests.peek() shouldBe emptyList()
            (borrowed.resolveCredentials() === owner.current.peek()) shouldBe true
            val refreshed = AwsBasicCredentials.create("synthetic-refreshed-key", "synthetic-refreshed-secret")
            owner.current.update { refreshed }
            (borrowed.resolveCredentials() === refreshed) shouldBe true
            owner.resolved.peek() shouldBe 2
        }
        test("identity resolution preserves the exact request future and failure") {
            val owner = CallerCredentials()
            val borrowed = borrowedAwsCredentialsProvider(owner)
            borrowed.identityType() shouldBe AwsCredentialsIdentity::class.java
            val request = ResolveIdentityRequest.builder().build()
            val original = owner.answer.peek()
            (borrowed.resolveIdentity(request) === original) shouldBe true
            (owner.requests.peek().single() === request) shouldBe true
            val failure = IllegalStateException("synthetic identity failure")
            val failed = CompletableFuture.failedFuture<AwsCredentialsIdentity>(failure)
            owner.answer.update { failed }
            val forwarded = borrowed.resolveIdentity(request)
            (forwarded === failed) shouldBe true
            shouldThrow<CompletionException>({ forwarded.join() }) { (it.cause === failure) shouldBe true }
            owner.resolved.peek() shouldBe 0
        }
        test("a synchronous provider failure propagates by identity") {
            val owner = CallerCredentials()
            val failure = IllegalStateException("synthetic credentials failure")
            owner.failure.update { failure }
            val borrowed = borrowedAwsCredentialsProvider(owner)
            shouldThrow<IllegalStateException>({ borrowed.resolveCredentials() }) { (it === failure) shouldBe true }
            owner.resolved.peek() shouldBe 1
            owner.closed.peek() shouldBe false
        }
    }
}

private class CallerCredentials : AwsCredentialsProvider, AutoCloseable {
    val resolved = Shared(0)
    val closed = Shared(false)
    val current = Shared<AwsCredentials>(AwsBasicCredentials.create("synthetic-key", "synthetic-secret"))
    val answer = Shared(CompletableFuture.completedFuture<AwsCredentialsIdentity>(current.peek()))
    val requests = Shared(emptyList<ResolveIdentityRequest>())
    val failure = Shared<IllegalStateException?>(null)
    override fun identityType(): Class<AwsCredentialsIdentity> = AwsCredentialsIdentity::class.java
    override fun resolveIdentity(request: ResolveIdentityRequest): CompletableFuture<AwsCredentialsIdentity> {
        requests.update { it + request }
        return answer.peek()
    }
    override fun resolveCredentials(): AwsCredentials {
        resolved.update { it + 1 }
        failure.peek()?.let { throw it }
        return current.peek()
    }
    override fun close() { closed.update { true } }
}
