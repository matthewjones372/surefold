# Files

`surefold-files` stores files in any S3-compatible bucket: AWS itself, Cloudflare R2, Hetzner Object Storage,
Backblaze B2 or MinIO. Every call answers its failure as a value, never as an SDK exception, runs under a deadline and
a retry policy, and has an in-memory fake that keeps the same contract for unit tests.

```kotlin
dependencies {
    implementation("dev.surefold:surefold-files:0.17.0")
    testImplementation("dev.surefold:surefold-files-test:0.17.0") // MinIO in Testcontainers
}
```

## A store

```kotlin
val files: Files = S3Files.of(
    FilesConfig(
        bucket = "tickets",
        region = "auto",
        accessKey = accessKey,
        secretKey = secretKey,
        endpoint = URI("https://<account>.r2.cloudflarestorage.com"),
        pathStyle = true,
        maxSize = 50L * 1024 * 1024,
    ),
)
```

`endpoint` is left out for AWS itself. Most other stores want `pathStyle`. `maxSize` is the largest file `put` and
`presignPut` accept. `FilesConfig.toString` leaves the secret out.

The SDK runs on the JDK's HTTP client, with its own retries off and its request checksums sent only when an operation
needs them, since not every S3-compatible store accepts the checksums it now sends by default.

## Keys and tenants

A `FileKey` is checked when it is made: not empty, at most 1,024 bytes, no leading `/`, no `.` or `..` segment, no
control characters. `TenantPrefix(tenant).key(path)` puts a tenant's files under `tenants/<tenant>/`, so a listing,
a bucket policy or a lifecycle rule on that prefix sees one tenant only, and a path that would climb out of it is
refused.

<!-- files -->
```kotlin
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.files.FileError
import dev.surefold.files.Files
import dev.surefold.files.Presigned
import dev.surefold.files.TenantPrefix
import java.io.InputStream
import kotlin.time.Duration.Companion.minutes

class Tickets(private val files: Files) {

    /** Stores a ticket under its tenant's prefix, so no tenant's listing ever shows another's. */
    fun store(tenant: String, bookingId: String, pdf: InputStream, size: Long): Either<FileError, String> = attempt {
        val key = TenantPrefix(tenant).key("tickets/$bookingId.pdf").bind()
        files.put(key, pdf, size, "application/pdf").bind().etag
    }

    /** A link the customer's browser can open for ten minutes, without credentials. */
    fun link(tenant: String, bookingId: String): Either<FileError, Presigned> = attempt {
        val key = TenantPrefix(tenant).key("tickets/$bookingId.pdf").bind()
        files.head(key).bind()
        files.presignGet(key, expiresIn = 10.minutes).bind()
    }
}
```

## What a call can answer

| `FileError` | When |
|---|---|
| `NotFound` | nothing is stored at the key |
| `AccessDenied` | the store refused the credentials or the bucket's policy did |
| `TooLarge` | a put, a presigned PUT or a completed upload over `maxSize` |
| `Unavailable` | the store could not be reached, answered 5xx or 429, or the policy's deadline passed or its breaker was open |
| `InvalidKey` | a key that `FileKey.of` would not make |

Every call runs under the store's `Policy`. The default is a ten-second deadline and up to three retries, doubling
from 200 ms with jitter, of `Unavailable` only. Pass another to `S3Files.of`.

## Presigned URLs and large files

`presignPut(key, expiresIn, contentType, size)` answers a URL, a method and the headers to send with it. The size
and the content type are signed, so a browser cannot upload more than was asked for. `presignGet(key, expiresIn)`
answers a URL to read the file without credentials. Neither lasts more than seven days.

`multipart(key, contentType)` starts an upload in parts. Parts are numbered from 1, may be sent from several threads
in any order, and on S3 each but the last is at least 5 MB. `complete()` joins them in number order; `abort()` drops
them, which matters on S3, where an upload left open keeps its parts and their cost.

## Testing

`InMemoryFiles` is a `Files` in memory with the same answers. `Minio.files()`, from `surefold-files-test`, starts
one S3-compatible server for the test JVM and answers a store on a fresh bucket each call. MinIO no longer publishes
public images, so it runs RustFS, `rustfs/rustfs:1.0.1`, a maintained server with MinIO's API; set the
`surefold.files.test.image` system property to run another, such as Bitnami's last MinIO,
`bitnamilegacy/minio:2025.7.23`.

## Metrics

| Metric | Kind | Tags |
|---|---|---|
| `surefold.files.duration.seconds` | histogram | `operation`: `put`, `get`, `head`, `delete`, `list`, `copy`, … |
| `surefold.files.bytes` | counter | `direction`: `in` or `out` |

Spans come from OpenTelemetry's own AWS SDK instrumentation, added to the `S3Client` the service builds and passes to
the `S3Files` constructor.
