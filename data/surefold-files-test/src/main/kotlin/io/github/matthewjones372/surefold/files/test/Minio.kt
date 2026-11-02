package io.github.matthewjones372.surefold.files.test

import dev.surefold.Policy
import io.github.matthewjones372.surefold.files.FilesConfig
import io.github.matthewjones372.surefold.files.S3Files
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/**
 * One S3-compatible server for the test JVM, in a container started on first use and stopped when the JVM exits,
 * and a fresh bucket in it for each [files]. MinIO no longer publishes public images, so the default is RustFS, a
 * maintained server that speaks MinIO's API, health check included; set `surefold.files.test.image` to use another,
 * such as `bitnamilegacy/minio:2025.7.23`. Each is given its credentials under both servers' names.
 */
object Minio {
    const val IMAGE = "rustfs/rustfs:1.0.1"
    private const val USER = "surefold"
    private const val PASSWORD = "surefold-test-secret"
    private const val PORT = 9000

    private val server: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse(System.getProperty("surefold.files.test.image", IMAGE)))
            .withEnv("MINIO_ROOT_USER", USER)
            .withEnv("MINIO_ROOT_PASSWORD", PASSWORD)
            .withEnv("RUSTFS_ACCESS_KEY", USER)
            .withEnv("RUSTFS_SECRET_KEY", PASSWORD)
            .withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(PORT))
            .apply { start() }
    }

    private val made = AtomicInteger()

    /** Where the server answers, for a service's own client. */
    val endpoint: URI get() = URI("http://${server.host}:${server.getMappedPort(PORT)}")

    /** The config of a fresh, empty bucket on the server. */
    fun config(maxSize: Long = Long.MAX_VALUE): FilesConfig {
        val config = FilesConfig(
            bucket = "surefold-${made.incrementAndGet()}",
            region = "us-east-1",
            accessKey = USER,
            secretKey = PASSWORD,
            endpoint = endpoint,
            pathStyle = true,
            maxSize = maxSize,
        )
        S3Files.of(config).use { it.createBucket() }
        return config
    }

    /** A [S3Files] on a fresh, empty bucket. */
    fun files(maxSize: Long = Long.MAX_VALUE, policy: Policy = S3Files.defaultPolicy): S3Files =
        S3Files.of(config(maxSize), policy)
}
