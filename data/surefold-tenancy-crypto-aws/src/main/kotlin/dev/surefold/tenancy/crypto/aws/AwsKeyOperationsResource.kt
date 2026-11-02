package dev.surefold.tenancy.crypto.aws

import dev.surefold.Resource
import dev.surefold.resource
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity
import software.amazon.awssdk.identity.spi.ResolveIdentityRequest
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.KmsClientBuilder
import java.util.concurrent.CompletableFuture

/** Owns SDK clients only; the caller selects roles and retains ownership of its refreshing provider. */
fun awsKeyOperations(
    regions: Set<String>,
    credentialsProvider: AwsCredentialsProvider,
    preferredDataRegion: String? = null,
): Resource<AwsKeyOperations> = ownedKeyOperations(regions, credentialsProvider, preferredDataRegion) { it.build() }

internal fun ownedKeyOperations(
    regions: Set<String>,
    credentialsProvider: AwsCredentialsProvider,
    preferredDataRegion: String?,
    build: (KmsClientBuilder) -> KmsClient,
): Resource<AwsKeyOperations> {
    val selected = regions.toList()
    // SDK configuration closes closeable providers; delegate identity resolution without transferring ownership.
    val borrowed = object : AwsCredentialsProvider by credentialsProvider {
        override fun identityType(): Class<AwsCredentialsIdentity> = credentialsProvider.identityType()
        override fun resolveIdentity(request: ResolveIdentityRequest): CompletableFuture<AwsCredentialsIdentity> =
            credentialsProvider.resolveIdentity(request)
    }
    return resource {
        fun client(region: String, attempts: Int) = install(
            acquire = {
                build(
                    KmsClient.builder().region(Region.of(region)).credentialsProvider(borrowed)
                        .httpClientBuilder(UrlConnectionHttpClient.builder())
                        .overrideConfiguration {
                            it.retryStrategy(
                                AwsRetryStrategy.standardRetryStrategy().toBuilder().maxAttempts(attempts).build(),
                            )
                        },
                )
            },
            release = { client, _ -> client.close() },
        )
        val clients = selected.map { region -> region to (client(region, 3) to client(region, 1)) }
        AwsKeyOperations(
            clients.associate { (region, pair) -> region to pair.first },
            clients.associate { (region, pair) -> region to pair.second },
            preferredDataRegion,
        )
    }
}
