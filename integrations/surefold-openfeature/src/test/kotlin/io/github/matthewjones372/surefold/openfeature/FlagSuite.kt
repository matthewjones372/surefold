package io.github.matthewjones372.surefold.openfeature

import dev.openfeature.sdk.Client
import dev.openfeature.sdk.ErrorCode
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.providers.memory.InMemoryProvider
import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import dev.surefold.parZip
import dev.surefold.resource
import dev.openfeature.sdk.providers.memory.Flag as MemoryFlag

private object TestFlags {
    val registryCheck = Flag.boolean("adoption.registry-check", default = true)
    val perAdopterLimit = Flag.int("adoption.per-customer-limit", default = 3)
    val greeting = Flag.string("adoption.greeting", default = "hello")
    val discount = Flag.double("adoption.discount", default = 0.0)
    val missing = Flag.int("adoption.nobody-declared-this", default = 7)
    val wrongType = Flag.boolean("adoption.per-customer-limit", default = false)
}

private fun <A> one(value: A): MemoryFlag<A> =
    MemoryFlag.builder<A>().variant("on", value).defaultVariant("on").build()

/** Spec 0181: a flag declared once, read with no client passed in. */
object FlagSuite : SharedSuite<Client>() {
    override val shared: Resource<Client> = resource {
        OpenFeatureAPI.getInstance().run {
            useSurefold()
            setProviderAndWait(
                "flags",
                InMemoryProvider(
                    mapOf(
                        "adoption.registry-check" to one(false),
                        "adoption.per-customer-limit" to one(5),
                        "adoption.greeting" to one("welcome"),
                        "adoption.discount" to one(0.25),
                    ),
                ),
            )
            getClient("flags")
        }
    }

    init {
        test("each factory reads its flag as the type it declares") { client ->
            featureClient.locally(client) {
                TestFlags.registryCheck.value() shouldBe false
                TestFlags.perAdopterLimit.value() shouldBe 5
                TestFlags.greeting.value() shouldBe "welcome"
                TestFlags.discount.value() shouldBe 0.25
            }
        }

        test("a flag no provider knows answers its default") { client ->
            featureClient.locally(client) {
                TestFlags.missing.value() shouldBe 7
                TestFlags.missing.details().errorCode shouldBe ErrorCode.FLAG_NOT_FOUND
            }
        }

        test("a flag read as the wrong type answers its default, with TYPE_MISMATCH") { client ->
            featureClient.locally(client) {
                val details = TestFlags.wrongType.details()

                details.value shouldBe false
                details.errorCode shouldBe ErrorCode.TYPE_MISMATCH
            }
        }

        test("a fork reads the client its opener bound") { client ->
            val (left, right) = featureClient.locally(client) {
                parZip({ TestFlags.perAdopterLimit.value() }, { TestFlags.greeting.value() }) { a, b -> a to b }
            }

            withClue("the default client has no provider for these flags, so a fork that lost the binding reads 3") {
                left shouldBe 5
                right shouldBe "welcome"
            }
        }

        test("with nothing bound, a flag reads the default client") { _ ->
            withClue("the default client belongs to no domain") {
                featureClient.get().metadata.domain shouldBe null
            }
            TestFlags.missing.key shouldBe "adoption.nobody-declared-this"
            TestFlags.missing.default shouldBe 7
        }
    }
}
