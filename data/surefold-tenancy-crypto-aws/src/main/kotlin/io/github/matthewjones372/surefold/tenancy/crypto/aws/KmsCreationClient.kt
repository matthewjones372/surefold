package io.github.matthewjones372.surefold.tenancy.crypto.aws

import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.ensure
import io.github.matthewjones372.surefold.tenancy.crypto.KeyFailureCode
import io.github.matthewjones372.surefold.tenancy.crypto.KeyOperation
import io.github.matthewjones372.surefold.tenancy.crypto.KeyOperationFailure
import software.amazon.awssdk.services.kms.KmsClient

/** Creation has no idempotency token; SDK retries can leave an uncheckpointed mutation. */
internal fun singleAttemptClient(
    clients: KmsObservations,
    region: String,
    operation: KeyOperation,
): Either<KeyOperationFailure, KmsClient> = attempt {
    val client = clients.client(region, operation).bind()
    val configuration = client.serviceClientConfiguration().overrideConfiguration()
    ensure(configuration?.retryStrategy()?.orElse(null)?.maxAttempts() == 1) {
        KeyOperationFailure(operation, KeyFailureCode.Refused)
    }
    client
}
