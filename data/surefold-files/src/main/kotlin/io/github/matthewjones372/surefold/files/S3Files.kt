package io.github.matthewjones372.surefold.files

import dev.surefold.Policy
import dev.surefold.Rejected
import dev.surefold.Schedule
import dev.surefold.counter
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.policy
import dev.surefold.timed
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.io.InputStream
import java.net.URI
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Where an S3-compatible bucket is, and how to sign in to it. [endpoint] is null for AWS itself; Cloudflare R2,
 * Hetzner, Backblaze and MinIO name theirs, and most want [pathStyle]. [maxSize] is the largest file a put accepts.
 */
data class FilesConfig(
    val bucket: String,
    val region: String,
    val accessKey: String,
    val secretKey: String,
    val endpoint: URI? = null,
    val pathStyle: Boolean = false,
    val maxSize: Long = FIVE_GIGABYTES,
) {
    override fun toString(): String =
        "FilesConfig(bucket=$bucket, region=$region, endpoint=$endpoint, pathStyle=$pathStyle, maxSize=$maxSize)"
}

/** S3's own limit on one PUT. */
private const val FIVE_GIGABYTES = 5L * 1024 * 1024 * 1024

/**
 * A [Files] on an S3-compatible bucket through [client]. Every call runs under [policy], by default a ten-second
 * deadline and up to three retries of what is worth retrying, and the SDK's own retries are left to it: the policy
 * is the one place a retry is decided. A failure answers as a [FileError], never as an SDK exception.
 */
class S3Files(
    private val client: S3Client,
    private val presigner: S3Presigner,
    private val bucket: String,
    override val maxSize: Long,
    private val policy: Policy = defaultPolicy,
) : Files, AutoCloseable {

    override fun put(
        key: FileKey,
        content: InputStream,
        size: Long,
        contentType: String,
    ): Either<FileError, FileMeta> {
        if (size > maxSize) return Either.Left(FileError.TooLarge(key, size, maxSize))
        return call("put", key) {
            client.putObject(
                { it.bucket(bucket).key(key.value).contentType(contentType) },
                RequestBody.fromInputStream(content, size),
            )
            counter(BYTES, "direction" to "in").increment(size.toDouble())
            headOf(key)
        }
    }

    override fun get(key: FileKey): Either<FileError, FileContent> = call("get", key) {
        val response = client.getObject { it.bucket(bucket).key(key.value) }
        val head = response.response()
        counter(BYTES, "direction" to "out").increment(head.contentLength().toDouble())
        val meta = FileMeta(key, head.contentLength(), head.contentType(), head.eTag().trim('"'), head.lastModified())
        FileContent(meta, response)
    }

    override fun head(key: FileKey): Either<FileError, FileMeta> = call("head", key) { headOf(key) }

    override fun delete(key: FileKey): Either<FileError, Unit> = call<Unit>("delete", key) {
        client.deleteObject { it.bucket(bucket).key(key.value) }
    }

    override fun list(prefix: String, after: String?, limit: Int): Either<FileError, FilePage> {
        require(limit in 1..MOST_LISTED) { "A listing takes 1 to $MOST_LISTED files, not $limit." }
        return call("list", null) {
            val response = client.listObjectsV2 { request ->
                request.bucket(bucket).prefix(prefix).maxKeys(limit)
                if (after != null) request.startAfter(after)
            }
            val files = response.contents().map { item ->
                val key = FileKey.of(item.key()).orThrow()
                FileMeta(key, item.size(), null, item.eTag().trim('"'), item.lastModified())
            }
            FilePage(files, if (response.isTruncated) files.lastOrNull()?.key?.value else null)
        }
    }

    override fun copy(from: FileKey, to: FileKey): Either<FileError, FileMeta> = call("copy", from) {
        client.copyObject { request ->
            request.sourceBucket(bucket).sourceKey(from.value).destinationBucket(bucket).destinationKey(to.value)
        }
        headOf(to)
    }

    override fun presignPut(
        key: FileKey,
        expiresIn: Duration,
        contentType: String,
        size: Long,
    ): Either<FileError, Presigned> {
        require(expiresIn > Duration.ZERO && expiresIn <= LONGEST_PRESIGN) { "A presigned URL lasts up to 7 days." }
        if (size > maxSize) return Either.Left(FileError.TooLarge(key, size, maxSize))
        return call("presign put", key) {
            presigner.presignPutObject { request ->
                request.signatureDuration(expiresIn.toJavaDuration()).putObjectRequest { put ->
                    put.bucket(bucket).key(key.value).contentType(contentType).contentLength(size)
                }
            }.let { Presigned(it.url().toURI(), "PUT", it.signedHeaders().toPlainHeaders(), it.expiration()) }
        }
    }

    override fun presignGet(key: FileKey, expiresIn: Duration): Either<FileError, Presigned> {
        require(expiresIn > Duration.ZERO && expiresIn <= LONGEST_PRESIGN) { "A presigned URL lasts up to 7 days." }
        return call("presign get", key) {
            presigner.presignGetObject { request ->
                request.signatureDuration(expiresIn.toJavaDuration())
                    .getObjectRequest { it.bucket(bucket).key(key.value) }
            }.let { Presigned(it.url().toURI(), "GET", it.signedHeaders().toPlainHeaders(), it.expiration()) }
        }
    }

    override fun multipart(key: FileKey, contentType: String): Either<FileError, MultipartUpload> =
        call("start multipart", key) {
            val started = client.createMultipartUpload { it.bucket(bucket).key(key.value).contentType(contentType) }
            S3Upload(key, started.uploadId())
        }

    private inner class S3Upload(override val key: FileKey, private val id: String) : MultipartUpload {
        private val etags = ConcurrentSkipListMap<Int, String>()

        override fun part(number: Int, content: InputStream, size: Long): Either<FileError, Unit> {
            require(number in 1..MOST_PARTS) { "Parts are numbered 1 to $MOST_PARTS, not $number." }
            return call<Unit>("upload part", key) {
                val etag = client.uploadPart(
                    { it.bucket(bucket).key(key.value).uploadId(id).partNumber(number).contentLength(size) },
                    RequestBody.fromInputStream(content, size),
                ).eTag()
                etags[number] = etag
                counter(BYTES, "direction" to "in").increment(size.toDouble())
            }
        }

        override fun complete(): Either<FileError, FileMeta> = call("complete multipart", key) {
            val parts = etags.map { (number, etag) -> CompletedPart.builder().partNumber(number).eTag(etag).build() }
            client.completeMultipartUpload { request ->
                request.bucket(bucket).key(key.value).uploadId(id).multipartUpload { it.parts(parts) }
            }
            headOf(key)
        }

        override fun abort(): Either<FileError, Unit> = call<Unit>("abort multipart", key) {
            client.abortMultipartUpload { it.bucket(bucket).key(key.value).uploadId(id) }
        }
    }

    override fun close() {
        presigner.close()
        client.close()
    }

    /** Creates this store's bucket, for a test or a first start; a bucket already there is left as it is. */
    fun createBucket(): Either<FileError, Unit> = call<Unit>("create bucket", null) {
        try {
            client.createBucket { it.bucket(bucket) }
        } catch (_: BucketAlreadyOwnedByYouException) {
            // Already there, and ours.
        }
    }

    private fun headOf(key: FileKey): FileMeta {
        val head = client.headObject { it.bucket(bucket).key(key.value) }
        return FileMeta(key, head.contentLength(), head.contentType(), head.eTag().trim('"'), head.lastModified())
    }

    /** Runs [block] under the policy, timed, with every way the SDK or the policy fails answered as a [FileError]. */
    private fun <A> call(operation: String, key: FileKey?, block: () -> A): Either<FileError, A> = attempt {
        timed(DURATION, "operation" to operation) {
            try {
                policy { block() }
            } catch (missing: NoSuchKeyException) {
                fail(key?.let { FileError.NotFound(it) } ?: FileError.Unavailable("$operation: ${missing.message}"))
            } catch (refused: S3Exception) {
                fail(refused.asError(key))
            } catch (unreached: SdkClientException) {
                fail(FileError.Unavailable("$operation could not reach the store: ${unreached.message}"))
            } catch (rejected: Rejected) {
                fail(FileError.Unavailable("$operation was refused by its policy: ${rejected.message}"))
            } catch (late: TimeoutException) {
                fail(FileError.Unavailable("$operation passed its deadline: ${late.message}"))
            }
        }
    }

    private fun S3Exception.asError(key: FileKey?): FileError = when (statusCode()) {
        NOT_FOUND -> key?.let { FileError.NotFound(it) } ?: FileError.Unavailable(message ?: "not found")
        FORBIDDEN -> FileError.AccessDenied(key, awsErrorDetails()?.errorMessage() ?: message ?: "access denied")
        else -> FileError.Unavailable("the store answered ${statusCode()}: ${awsErrorDetails()?.errorMessage()}")
    }

    companion object {
        private const val DURATION = "surefold.files.duration.seconds"
        private const val BYTES = "surefold.files.bytes"
        private const val MOST_LISTED = 1_000
        private const val MOST_PARTS = 10_000
        private const val NOT_FOUND = 404
        private const val FORBIDDEN = 403
        private const val THROTTLED = 429
        private const val SERVER = 500

        /** Worth another try: the store unreached, throttling, or failing on its side. */
        private fun retryable(thrown: Throwable): Boolean = when (thrown) {
            is S3Exception -> thrown.statusCode() == THROTTLED || thrown.statusCode() >= SERVER
            is SdkClientException -> true
            else -> false
        }

        /** Ten seconds in all, and up to three retries, doubling from 200 ms, of what is worth retrying. */
        val defaultPolicy: Policy = policy("files") {
            deadline(10.seconds)
            retry(
                Schedule.exponential<Throwable>(200.milliseconds).jittered(0.5, 1.5)
                    .and(Schedule.recurs(3))
                    .doWhile { thrown, _ -> retryable(thrown) },
            )
        }

        /** A store on [config], its client on the JDK's HTTP client, with the SDK's own retries and checksums off. */
        fun of(config: FilesConfig, policy: Policy = defaultPolicy): S3Files {
            val client = S3Client.builder()
                .region(Region.of(config.region))
                .credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKey, config.secretKey)),
                )
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .forcePathStyle(config.pathStyle)
                .overrideConfiguration { it.retryStrategy(AwsRetryStrategy.doNotRetry()) }
                // S3-compatible stores do not all take the checksums the SDK now sends by default.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .apply { config.endpoint?.let { endpointOverride(it) } }
                .build()
            val presigner = S3Presigner.builder()
                .region(Region.of(config.region))
                .credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKey, config.secretKey)),
                )
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(config.pathStyle).build())
                .apply { config.endpoint?.let { endpointOverride(it) } }
                .build()
            return S3Files(client, presigner, config.bucket, config.maxSize, policy)
        }
    }
}

/** A presigned request's signed headers, each with its one value, which is all S3 ever signs. */
private fun Map<String, List<String>>.toPlainHeaders(): Map<String, String> =
    filterKeys { !it.equals("host", ignoreCase = true) }.mapValues { (_, values) -> values.joinToString(",") }

/** The key, for a key already checked once: a store only hands back keys that were put through [FileKey.of]. */
internal fun Either<FileError.InvalidKey, FileKey>.orThrow(): FileKey = when (this) {
    is Either.Right -> value
    is Either.Left -> error("the store holds a key surefold-files would not make: $value")
}
