package io.github.matthewjones372.surefold.openfeature

import dev.openfeature.sdk.ImmutableContext
import dev.openfeature.sdk.ImmutableStructure
import dev.openfeature.sdk.Value
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.withClue

private val carrier = FeatureContextCarrier()

private fun across(context: ImmutableContext) =
    featureContext.locally(context) { carrier.capture() }.let { carried ->
        carrier.within(carried) { featureContext.get() }
    }

/** What crosses a message: the targeting key and every scalar attribute, each as the type it was. */
object FeatureContextCarrierSuite : Suite() {
    init {
        test("nothing is carried when no context is bound") {
            carrier.capture().shouldBeEmpty()
        }

        test("a string, an int, a long, a double and a boolean arrive as what they were") {
            val sent = ImmutableContext(
                "ada",
                mapOf(
                    "tier" to Value("gold"),
                    "limit" to Value(3),
                    "since" to Value(1_700_000_000_000L),
                    "ratio" to Value(0.5),
                    "beta" to Value(true),
                ),
            )

            val arrived = across(sent)

            arrived.targetingKey shouldBe "ada"
            arrived.getValue("tier").asObject() shouldBe "gold"
            arrived.getValue("limit").asObject() shouldBe 3
            arrived.getValue("since").asObject() shouldBe 1_700_000_000_000L
            arrived.getValue("ratio").asObject() shouldBe 0.5
            arrived.getValue("beta").asObject() shouldBe true
        }

        test("a structure or a list is left behind, and the scalars beside it still cross") {
            val sent = ImmutableContext(
                "ada",
                mapOf(
                    "address" to Value(ImmutableStructure(mapOf("city" to Value("Paris")))),
                    "roles" to Value(listOf(Value("admin"))),
                    "tier" to Value("gold"),
                ),
            )

            val arrived = across(sent)

            withClue("only scalars have a string form both ends agree on") {
                arrived.keySet() shouldBe setOf("targetingKey", "tier")
            }
        }

        test("keys that are not this carrier's are left alone") {
            carrier.within(mapOf("traceparent" to "00-abc")) { featureContext.get().isEmpty } shouldBe true
        }
    }
}
