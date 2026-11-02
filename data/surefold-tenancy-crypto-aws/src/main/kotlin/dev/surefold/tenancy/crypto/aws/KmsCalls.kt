package dev.surefold.tenancy.crypto.aws

import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.mapLeft
import dev.surefold.fail.nonFatalOrThrow
import dev.surefold.tenancy.crypto.KeyFailureCode
import dev.surefold.tenancy.crypto.KeyOperation
import dev.surefold.tenancy.crypto.KeyOperationFailure
import software.amazon.awssdk.core.exception.AbortedException
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.kms.model.AlreadyExistsException
import software.amazon.awssdk.services.kms.model.ConflictException
import software.amazon.awssdk.services.kms.model.DisabledException
import software.amazon.awssdk.services.kms.model.IncorrectKeyException
import software.amazon.awssdk.services.kms.model.InvalidAliasNameException
import software.amazon.awssdk.services.kms.model.InvalidArnException
import software.amazon.awssdk.services.kms.model.InvalidCiphertextException
import software.amazon.awssdk.services.kms.model.InvalidKeyUsageException
import software.amazon.awssdk.services.kms.model.KmsException
import software.amazon.awssdk.services.kms.model.KmsInvalidStateException
import software.amazon.awssdk.services.kms.model.MalformedPolicyDocumentException
import software.amazon.awssdk.services.kms.model.NotFoundException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.nio.channels.ClosedByInterruptException

internal fun <A> kmsCall(
    operation: KeyOperation,
    mutation: Boolean = false,
    call: () -> Either<KeyOperationFailure, A>,
): Either<KeyOperationFailure, A> = try {
    call().mapLeft { it.copy(operation = operation) }
} catch (refused: KmsException) {
    propagateCancellation(refused)
    KeyOperationFailure(operation, refusal(refused, mutation)).left()
} catch (unreachable: SdkClientException) {
    propagateCancellation(unreachable)
    KeyOperationFailure(operation, unknownFailure(mutation)).left()
}

private fun refusal(refused: KmsException, mutation: Boolean): KeyFailureCode = when (refused) {
    is InvalidCiphertextException, is IncorrectKeyException -> KeyFailureCode.InvalidCiphertext

    is NotFoundException -> KeyFailureCode.NotObserved

    is DisabledException, is KmsInvalidStateException, is InvalidKeyUsageException -> KeyFailureCode.Refused

    is AlreadyExistsException, is ConflictException -> KeyFailureCode.Conflict

    is InvalidArnException, is InvalidAliasNameException, is MalformedPolicyDocumentException ->
        KeyFailureCode.InvalidInput

    else -> serviceFailure(refused, mutation)
}

private fun serviceFailure(refused: KmsException, mutation: Boolean): KeyFailureCode =
    when (refused.awsErrorDetails()?.errorCode()) {
        "AccessDeniedException", "NotAuthorized", "ExpiredTokenException", "UnrecognizedClientException" ->
            KeyFailureCode.Refused

        "ValidationException", "ValidationError", "InvalidParameterException", "TagException" ->
            KeyFailureCode.InvalidInput

        "ThrottlingException", "LimitExceededException" -> KeyFailureCode.Unavailable

        else -> if (refused.statusCode() == 403 || refused.statusCode() == 401) KeyFailureCode.Refused
        else unknownFailure(mutation)
    }

/** A transport or unclassified service failure cannot prove that a mutation was refused. */
private fun unknownFailure(mutation: Boolean) =
    if (mutation) KeyFailureCode.UncertainMutation else KeyFailureCode.Unavailable

/** Socket timeouts are lost replies; an SDK abort or an interrupted call retains cancellation semantics. */
internal tailrec fun propagateCancellation(thrown: Throwable?, seen: Set<Throwable> = emptySet()) {
    if (thrown == null || thrown in seen) return
    thrown.nonFatalOrThrow()
    if (thrown is AbortedException || thrown is ClosedByInterruptException) throw thrown
    if (thrown is InterruptedIOException && thrown !is SocketTimeoutException) throw thrown
    propagateCancellation(thrown.cause, seen + thrown)
}
