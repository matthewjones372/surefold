package io.github.matthewjones372.surefold.tenancy.crypto.aws

import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right
import io.github.matthewjones372.surefold.tenancy.crypto.GeneratedKey
import io.github.matthewjones372.surefold.tenancy.crypto.KeyContext
import io.github.matthewjones372.surefold.tenancy.crypto.KeyError
import io.github.matthewjones372.surefold.tenancy.crypto.KeyOwner
import io.github.matthewjones372.surefold.tenancy.crypto.Kms
import io.github.matthewjones372.surefold.tenancy.crypto.RootKey
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.model.DataKeySpec
import software.amazon.awssdk.services.kms.model.DisabledException
import software.amazon.awssdk.services.kms.model.IncorrectKeyException
import software.amazon.awssdk.services.kms.model.InvalidCiphertextException
import software.amazon.awssdk.services.kms.model.KmsException
import software.amazon.awssdk.services.kms.model.KmsInvalidStateException
import software.amazon.awssdk.services.kms.model.NotFoundException
import software.amazon.awssdk.services.kms.model.Tag
import kotlin.time.Duration

/**
 * [Kms] on AWS KMS: one symmetric customer managed key per owner, found by the alias [aliasPrefix] plus the owner's
 * tenant id, or `platform`, and made on its first [createRoot]. A root is its key's ARN. The context is the KMS
 * encryption context, so a key policy can refuse one tenant's caller another tenant's unwrap.
 */
class AwsKms(private val client: KmsClient, private val aliasPrefix: String) : Kms {

    init {
        require(aliasPrefix.startsWith("alias/") && aliasPrefix.endsWith("/")) {
            "an alias prefix is alias/…/, such as alias/bookings/prod/tenant/, not $aliasPrefix"
        }
    }

    override fun createRoot(owner: KeyOwner): Either<KeyError, RootKey> = called {
        val alias = aliasPrefix + aliasOf(owner)
        val existing = try {
            client.describeKey { it.keyId(alias) }.keyMetadata().arn()
        } catch (_: NotFoundException) {
            null
        }
        RootKey(existing ?: made(alias, owner)).right()
    }

    override fun generateDataKey(root: RootKey, context: KeyContext): Either<KeyError, GeneratedKey> = called {
        val made = client.generateDataKey {
            it.keyId(root.id).keySpec(DataKeySpec.AES_256).encryptionContext(context.entries())
        }
        GeneratedKey(made.plaintext().asByteArray(), made.ciphertextBlob().asByteArray()).right()
    }

    override fun decrypt(root: RootKey, wrapped: ByteArray, context: KeyContext): Either<KeyError, ByteArray> = called {
        client.decrypt {
            it.keyId(root.id).ciphertextBlob(SdkBytes.fromByteArray(wrapped)).encryptionContext(context.entries())
        }.plaintext().asByteArray().right()
    }

    /** KMS waits between 7 and 30 days, so [waiting] is rounded into that range; the alias goes at once. */
    override fun scheduleDeletion(root: RootKey, waiting: Duration): Either<KeyError, Unit> = called {
        val aliases = client.listAliases { it.keyId(root.id) }.aliases().map { it.aliasName() }
        aliases.filter { it.startsWith(aliasPrefix) }.forEach { name -> client.deleteAlias { it.aliasName(name) } }
        client.scheduleKeyDeletion {
            it.keyId(root.id).pendingWindowInDays(waiting.inWholeDays.toInt().coerceIn(MIN_DAYS, MAX_DAYS))
        }
        Unit.right()
    }

    private fun made(alias: String, owner: KeyOwner): String {
        val arn = client.createKey { request ->
            request.description("surefold-tenancy-crypto root key for ${aliasOf(owner)}")
                .tags(Tag.builder().tagKey("surefold:owner").tagValue(aliasOf(owner)).build())
        }.keyMetadata().arn()
        client.createAlias { it.aliasName(alias).targetKeyId(arn) }
        return arn
    }

    private fun aliasOf(owner: KeyOwner): String = when (owner) {
        is KeyOwner.Tenant -> owner.id.value.also { id ->
            require(ALIAS.matches(id)) { "tenant id $id cannot name a KMS alias: use letters, digits, / _ and -" }
        }

        KeyOwner.Platform -> "platform"
    }

    /** [call]'s answer, or the [KeyError] its KMS exception means. */
    private fun <A> called(call: () -> Either<KeyError, A>): Either<KeyError, A> = try {
        call()
    } catch (refused: KmsException) {
        refusal(refused).left()
    } catch (unreachable: SdkClientException) {
        KeyError.Unavailable(unreachable.message ?: "KMS could not be reached").left()
    }

    private fun refusal(refused: KmsException): KeyError {
        val reason = refused.awsErrorDetails()?.errorMessage() ?: refused.message ?: refused.javaClass.simpleName
        return when {
            refused is InvalidCiphertextException || refused is IncorrectKeyException || refused is NotFoundException ->
                KeyError.Invalid(reason)

            refused is DisabledException || refused is KmsInvalidStateException -> KeyError.Denied(reason)

            refused.statusCode() == FORBIDDEN || refused.awsErrorDetails()?.errorCode() == "AccessDeniedException" ->
                KeyError.Denied(reason)

            else -> KeyError.Unavailable(reason)
        }
    }

    companion object {
        /** A client on the JDK's own HTTP client, from the default region and credentials chain. */
        fun client(): KmsClient = KmsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build()

        private val ALIAS = Regex("[A-Za-z0-9/_-]+")
        private const val MIN_DAYS = 7
        private const val MAX_DAYS = 30
        private const val FORBIDDEN = 403
    }
}
