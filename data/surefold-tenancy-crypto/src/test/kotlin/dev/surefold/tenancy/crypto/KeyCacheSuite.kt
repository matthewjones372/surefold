package dev.surefold.tenancy.crypto

import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.fail.map
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.parMap
import dev.surefold.tenancy.TenantId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

/** Keys served from memory until soft, still served until hard while the KMS is down, never after (spec 0235). */
object KeyCacheSuite : Suite() {

    private val acme = KeyOwner.Tenant(TenantId("acme"))
    private val active = KeyCache.Slot(acme, KeyPurpose.Enc, null)

    /** A KMS, one key wrapped by it, and a load that unwraps that key, as TenantKeys's would. */
    private class Unwrapping {
        val kms = InMemoryKms()
        private val root = (kms.createRoot(acme) as Either.Right).value
        private val context = KeyContext(acme, KeyPurpose.Enc, 1)
        private val wrapped = (kms.generateDataKey(root, context) as Either.Right).value.wrapped

        fun load(): Either<KeyError, DataKey> =
            kms.decrypt(root, wrapped, context).map { DataKey(acme, KeyPurpose.Enc, 1, it) }
    }

    init {
        test("with the KMS down, a key is served at 59 minutes and refused at 61") {
            val time = TestClock()
            val unwrapping = Unwrapping()
            val cache = KeyCache(soft = 15.minutes, hard = 60.minutes)
            clock.locally(time) {
                cache.get(active, unwrapping::load).shouldBeInstanceOf<Either.Right<DataKey>>()
                unwrapping.kms.unavailable = true

                time.adjust(59.minutes)
                cache.get(active, unwrapping::load).shouldBeInstanceOf<Either.Right<DataKey>>()

                time.adjust(2.minutes)
                cache.get(active, unwrapping::load).shouldBeInstanceOf<Either.Left<KeyError.Unavailable>>()
            }
        }

        test("the KMS is asked once per soft period, however many threads ask at once") {
            val time = TestClock()
            val unwrapping = Unwrapping()
            val cache = KeyCache(soft = 15.minutes)
            val asked = CountDownLatch(16)
            clock.locally(time) {
                parMap((1..16).toList()) {
                    asked.countDown()
                    asked.await(5, TimeUnit.SECONDS)
                    cache.get(active, unwrapping::load)
                }.all { it is Either.Right } shouldBe true
                unwrapping.kms.decrypts shouldBe 1

                time.adjust(14.minutes)
                cache.get(active, unwrapping::load)
                unwrapping.kms.decrypts shouldBe 1

                time.adjust(2.minutes)
                cache.get(active, unwrapping::load)
                unwrapping.kms.decrypts shouldBe 2
            }
        }

        test("an evicted owner's keys are unwrapped again, and a key already handed out is not zeroed under it") {
            val unwrapping = Unwrapping()
            val cache = KeyCache()
            val handed = (cache.get(active, unwrapping::load) as Either.Right).value
            val bytes = handed.material.toList()

            cache.evict(acme)

            handed.material.toList() shouldBe bytes
            (cache.get(active, unwrapping::load) as Either.Right).value.material.toList() shouldBe bytes
            unwrapping.kms.decrypts shouldBe 2
        }

        test("past its size, the least recently used owner's keys go") {
            val unwrapping = Unwrapping()
            val cache = KeyCache(tenants = 1)
            val other = KeyCache.Slot(KeyOwner.Platform, KeyPurpose.Enc, null)
            val stub = (unwrapping.load() as Either.Right).value
            cache.get(active, unwrapping::load)

            cache.get(other) { Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, stub.material.copyOf())) }
            cache.get(active, unwrapping::load)

            unwrapping.kms.decrypts shouldBe 3
        }
    }
}
