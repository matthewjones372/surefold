package io.github.matthewjones372.surefold.cache.redis

import dev.surefold.cache.Cache
import dev.surefold.cache.Invalidation
import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.test.eventually
import io.lettuce.core.RedisClient
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.codec.StringCodec
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Ints as their decimal text. */
private object Decimal : Codec<Int> {
    override fun encode(value: Int): ByteArray = value.toString().encodeToByteArray()

    override fun decode(bytes: ByteArray): Int = bytes.decodeToString().toInt()
}

/** Redis as the layer every instance shares, and its pub/sub as how they hear a value changed (spec 0246). */
object RedisSuite : Suite() {

    private val redis: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse("public.ecr.aws/docker/library/redis:7.4")).withExposedPorts(PORT)
            .also { it.start() }
    }

    private val client: RedisClient by lazy { RedisClient.create("redis://${redis.host}:${redis.getMappedPort(PORT)}") }

    private fun bytes() = client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE))

    private fun instance(prefix: String, lookups: AtomicInteger) =
        Cache<String, Nothing, Int>({ 1.hours }, layers = listOf(RedisLayer(bytes(), prefix, Decimal))) {
            Either.Right(lookups.incrementAndGet())
        }

    init {
        test("a value one instance looked up is read by another with an empty memory, without its lookup") {
            val lookups = AtomicInteger()
            val first = instance("shared", lookups)
            val second = instance("shared", lookups)
            first.get("sku") shouldBe Either.Right(1)
            second.get("sku") shouldBe Either.Right(1)
            lookups.get() shouldBe 1
            second.stats().layerHits shouldBe 1
        }

        test("Redis expires a value when the cache would") {
            val lookups = AtomicInteger()
            val brief = Cache<String, Nothing, Int>(
                { 1.seconds },
                layers = listOf(RedisLayer(bytes(), "brief", Decimal)),
            ) { Either.Right(lookups.incrementAndGet()) }
            brief.get("sku")
            val connection = bytes()
            (connection.sync().pttl("brief:sku") in 1..1_000) shouldBe true
            eventually(within = 5.seconds) { connection.sync().exists("brief:sku") shouldBe 0L }
        }

        test("a published invalidation drops the key from another instance's memory, and the next read looks up") {
            val lookups = AtomicInteger()
            val first = instance("heard", lookups)
            val second = instance("heard", lookups)
            // Only the second listens: the first is the writer, and has dropped its own copy and Redis's already.
            val listening = RedisInvalidations.listen(client, "prices", second) { key -> key }
            first.get("sku") shouldBe Either.Right(1)
            second.get("sku") shouldBe Either.Right(1)

            first.invalidate(Invalidation.Key("sku"))
            RedisInvalidations.publish(client.connect().sync(), "prices", "sku")

            eventually(within = 5.seconds) { second.get("sku") shouldBe Either.Right(2) }
            first.get("sku") shouldBe Either.Right(2)
            listening.close()
        }
    }

    private const val PORT = 6379
}
