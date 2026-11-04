package dev.surefold.tenancy.crypto.aws

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity
import software.amazon.awssdk.identity.spi.ResolveIdentityRequest
import java.util.concurrent.CompletableFuture

/** Leaves provider lifetime with its caller when SDK clients close their configured credentials. */
fun borrowedAwsCredentialsProvider(provider: AwsCredentialsProvider): AwsCredentialsProvider =
    object : AwsCredentialsProvider by provider {
        override fun identityType(): Class<AwsCredentialsIdentity> = provider.identityType()
        override fun resolveIdentity(request: ResolveIdentityRequest): CompletableFuture<AwsCredentialsIdentity> =
            provider.resolveIdentity(request)
    }
