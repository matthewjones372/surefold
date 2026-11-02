package io.github.matthewjones372.surefold.tenancy.crypto.aws

import dev.surefold.fail.Either
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import io.github.matthewjones372.surefold.tenancy.crypto.KeyFailureCode
import io.github.matthewjones372.surefold.tenancy.crypto.KeyOperation
import io.github.matthewjones372.surefold.tenancy.crypto.KeyOperationFailure
import io.github.matthewjones372.surefold.tenancy.crypto.RootKey
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.exception.AbortedException
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.kms.model.AlreadyExistsException
import software.amazon.awssdk.services.kms.model.DisabledException
import software.amazon.awssdk.services.kms.model.IncorrectKeyException
import software.amazon.awssdk.services.kms.model.InvalidArnException
import software.amazon.awssdk.services.kms.model.InvalidCiphertextException
import software.amazon.awssdk.services.kms.model.KmsException
import software.amazon.awssdk.services.kms.model.KmsInvalidStateException
import software.amazon.awssdk.services.kms.model.NotFoundException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException

object KmsFoundationSuite : Suite() {
    init {
        test("canonical UUID and multi Region key ARNs retain every identity component") {
            for ((partition, region) in listOf(
                "aws" to "eu-west-2", "aws-cn" to "cn-north-1",
                "aws-us-gov" to "us-gov-east-1",
            )) {
                for (key in listOf(UUID, mrk)) {
                    val root = RootKey("arn:$partition:kms:$region:$ACCOUNT:key/$key")
                    val identity = parsed(root)
                    identity.location.root shouldBe root
                    identity.location.partition shouldBe partition
                    identity.location.region shouldBe region
                    identity.location.account shouldBe ACCOUNT
                    identity.keyId shouldBe key
                    identity.toString() shouldBe "KmsIdentity(redacted)"
                }
            }
        }

        test("invalid identities fail before the provider call") {
            val invalid = listOf(
                "alias/application/root", UUID, "arn:aws:kms:eu-west-2:$ACCOUNT:alias/root",
                ARN.replace("arn:", "ARN:"), ARN.replace(":kms:", ":s3:"),
                ARN.replace(":aws:", ":unknown:"), ARN.replace(":eu-west-2:", ":cn-north-1:"),
                ARN.replace(":eu-west-2:", "::"), ARN.replace(":eu-west-2:", ":EU-west-2:"),
                ARN.replace(":eu-west-2:", ":eu_west_2:"), ARN.replace(ACCOUNT, "123"),
                ARN.replace(ACCOUNT, "12345678901x"), ARN.replace(UUID, "1-2-3-4-5"),
                ARN.replace(UUID, UUID.uppercase()), ARN.replace(UUID, "mrk-${"a".repeat(31)}"),
                ARN.replace(UUID, "mrk-${"A".repeat(32)}"), "$ARN/extra", " $ARN", "$ARN\n",
            )
            for (candidate in invalid) {
                KmsIdentity.parse(RootKey(candidate), KeyOperation.Generate) shouldBe
                    Either.Left(KeyOperationFailure(KeyOperation.Generate, KeyFailureCode.InvalidInput))
            }
        }

        test("returned metadata must match the requested ARN account and stable key id") {
            val identity = parsed(RootKey(ARN))
            identity.verify(ARN, UUID, ACCOUNT, KeyOperation.Generate) shouldBe Unit.right()
            for ((returnedArn, returnedId, returnedAccount) in listOf(
                Triple(ARN.replace("eu-west-2", "eu-west-1"), UUID, ACCOUNT),
                Triple(ARN.replace(ACCOUNT, "999999999999"), UUID, "999999999999"),
                Triple(ARN.replace("aws:kms:eu-west-2", "aws-cn:kms:cn-north-1"), UUID, ACCOUNT),
                Triple(ARN.replace(UUID, mrk), mrk, ACCOUNT), Triple(ARN, mrk, ACCOUNT),
                Triple(ARN, UUID, "999999999999"),
            )) {
                identity.verify(returnedArn, returnedId, returnedAccount, KeyOperation.Generate) shouldBe
                    Either.Left(KeyOperationFailure(KeyOperation.Generate, KeyFailureCode.Conflict))
            }
        }

        test("missing or malformed returned identity is incomplete evidence") {
            val identity = parsed(RootKey(ARN))
            for ((returnedArn, returnedId, returnedAccount) in listOf(
                Triple(null, UUID, ACCOUNT), Triple(ARN, null, ACCOUNT), Triple(ARN, UUID, null),
                Triple("alias/root", UUID, ACCOUNT), Triple("", UUID, ACCOUNT),
            )) {
                identity.verify(returnedArn, returnedId, returnedAccount, KeyOperation.Describe) shouldBe
                    Either.Left(KeyOperationFailure(KeyOperation.Describe, KeyFailureCode.IncompleteEvidence))
            }
        }

        test("successful calls and declared failures pass through the boundary") {
            kmsCall(KeyOperation.Describe) { "observed".right() } shouldBe "observed".right()
            val failure = Either.Left(KeyOperationFailure(KeyOperation.Generate, KeyFailureCode.IncompleteEvidence))
            kmsCall<Unit>(KeyOperation.Generate) { failure } shouldBe failure
        }

        test("a nested observation failure retains the public operation identity") {
            kmsCall<Unit>(KeyOperation.Generate) {
                Either.Left(KeyOperationFailure(KeyOperation.Describe, KeyFailureCode.IncompleteEvidence))
            } shouldBe Either.Left(KeyOperationFailure(KeyOperation.Generate, KeyFailureCode.IncompleteEvidence))
        }

        test("provider exceptions become closed failures without messages or request details") {
            val failures = listOf(
                InvalidCiphertextException.builder().message(SECRET).build() to KeyFailureCode.InvalidCiphertext,
                IncorrectKeyException.builder().message(SECRET).build() to KeyFailureCode.InvalidCiphertext,
                NotFoundException.builder().message(SECRET).build() to KeyFailureCode.NotObserved,
                DisabledException.builder().message(SECRET).build() to KeyFailureCode.Refused,
                KmsInvalidStateException.builder().message(SECRET).build() to KeyFailureCode.Refused,
                AlreadyExistsException.builder().message(SECRET).build() to KeyFailureCode.Conflict,
                InvalidArnException.builder().message(SECRET).build() to KeyFailureCode.InvalidInput,
                service("AccessDeniedException", 400) to KeyFailureCode.Refused,
                service("Unknown", 403) to KeyFailureCode.Refused,
                service("ValidationException", 400) to KeyFailureCode.InvalidInput,
                service("ThrottlingException", 400) to KeyFailureCode.Unavailable,
                service("Unknown", 503) to KeyFailureCode.Unavailable,
                service("Unknown", 400) to KeyFailureCode.Unavailable,
                SdkClientException.create(SECRET) to KeyFailureCode.Unavailable,
            )
            for ((exception, code) in failures) {
                kmsCall<Unit>(KeyOperation.Unwrap) { throw exception } shouldBe
                    Either.Left(KeyOperationFailure(KeyOperation.Unwrap, code))
            }
        }

        test("a lost mutation reply remains uncertain and is never retried by the boundary") {
            for (exception in listOf(
                SdkClientException.create(SECRET), SdkClientException.create(SECRET, SocketTimeoutException(SECRET)),
                service("Unknown", 500), service("Unknown", 400), service("DependencyTimeoutException", 500),
            )) {
                var calls = 0
                kmsCall<Unit>(KeyOperation.CreatePrimary, mutation = true) {
                    calls += 1
                    throw exception
                } shouldBe
                    Either.Left(KeyOperationFailure(KeyOperation.CreatePrimary, KeyFailureCode.UncertainMutation))
                calls shouldBe 1
            }
        }

        test("explicit mutation refusals keep their closed meaning") {
            for ((exception, code) in listOf(
                service("ThrottlingException", 400) to KeyFailureCode.Unavailable,
                service("AccessDeniedException", 403) to KeyFailureCode.Refused,
                AlreadyExistsException.builder().message(SECRET).build() to KeyFailureCode.Conflict,
                InvalidArnException.builder().message(SECRET).build() to KeyFailureCode.InvalidInput,
            )) {
                kmsCall<Unit>(KeyOperation.BindAlias, mutation = true) { throw exception } shouldBe
                    Either.Left(KeyOperationFailure(KeyOperation.BindAlias, code))
            }
        }

        test("programmer exceptions and direct cancellation propagate unchanged") {
            val bug = IllegalStateException(SECRET)
            propagates(bug) { kmsCall<Unit>(KeyOperation.Describe) { throw bug } }
            val cancellation = CancellationException(SECRET)
            propagates(cancellation) { kmsCall<Unit>(KeyOperation.Describe) { throw cancellation } }
            val interrupted = InterruptedException(SECRET)
            propagates(interrupted) { kmsCall<Unit>(KeyOperation.Describe) { throw interrupted } }
        }

        test("SDK wrapped cancellation and interrupted causes propagate unchanged") {
            val cancellation = CancellationException(SECRET)
            val interrupted = InterruptedException(SECRET)
            val interruptedIo = InterruptedIOException(SECRET)
            propagates(cancellation) {
                kmsCall<Unit>(KeyOperation.CreatePrimary, mutation = true) {
                    throw SdkClientException.create(SECRET, IllegalStateException(SECRET, cancellation))
                }
            }
            propagates(interrupted) {
                kmsCall<Unit>(KeyOperation.Describe) { throw service("Unknown", 500, interrupted) }
            }
            propagates(interruptedIo) {
                kmsCall<Unit>(KeyOperation.Describe) { throw SdkClientException.create(SECRET, interruptedIo) }
            }
            val aborted = AbortedException.create(SECRET)
            propagates(aborted) { kmsCall<Unit>(KeyOperation.Describe) { throw aborted } }
        }
    }
}

private const val ACCOUNT = "123456789012"
private const val UUID = "12345678-1234-1234-1234-123456789abc"
private val mrk = "mrk-${"a".repeat(32)}"
private const val ARN = "arn:aws:kms:eu-west-2:$ACCOUNT:key/$UUID"
private const val SECRET = "secret-context secret-policy secret-bytes secret-request-id"

private fun parsed(root: RootKey): KmsIdentity =
    (KmsIdentity.parse(root, KeyOperation.Describe) as Either.Right).value

private fun service(code: String, status: Int, cause: Throwable? = null): KmsException =
    KmsException.builder().message(SECRET).requestId(SECRET).statusCode(status).cause(cause)
        .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage(SECRET).build()).build() as KmsException

private inline fun <reified T : Throwable> propagates(expected: T, block: () -> Any?) =
    shouldThrow<T>(block) { it shouldBe expected }
