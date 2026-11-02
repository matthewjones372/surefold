package io.github.matthewjones372.surefold.openfeature

import dev.openfeature.sdk.ImmutableStructure
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.Value
import dev.openfeature.sdk.providers.memory.InMemoryProvider
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.withClue
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import dev.openfeature.sdk.providers.memory.Flag as MemoryFlag

private enum class Mode { Classic, Express }

private data class Hours(val from: Int, val to: Int)

private fun hours(value: Value): Hours =
    value.asStructure().let { Hours(it.getValue("from").asInteger(), it.getValue("to").asInteger()) }

private fun structure(vararg fields: Pair<String, Value>) = Value(ImmutableStructure(mapOf(*fields)))

/** One declared flag, a value it reads and one it does not, with what [Flag.rejects] says of the second. */
private class Case(val flag: Flag<*>, val good: Value, val bad: Value, val says: String)

private val cases = listOf(
    Case(Flag.boolean("rejects.boolean", true), Value(false), Value("false"), "is a string, not a Boolean"),
    Case(Flag.string("rejects.string", ""), Value("on"), Value(true), "is a boolean, not a String"),
    Case(Flag.int("rejects.int", 1), Value(5), Value("5x"), "is a string, not an Int"),
    Case(Flag.double("rejects.double", 1.0), Value(0.5), Value("half"), "is a string, not a Double"),
    Case(Flag.long("rejects.long", 1L), Value(5_000_000_000L), Value(5.5), "5.5 is not a whole number"),
    Case(Flag.duration("rejects.duration", 2.seconds), Value("5s"), Value("5x"), "\"5x\" did not decode as a Duration"),
    Case(
        Flag.instant("rejects.instant", Instant.MAX),
        Value("2026-01-01T00:00:00Z"),
        Value("tomorrow"),
        "\"tomorrow\" did not decode as an Instant",
    ),
    Case(Flag.enum("rejects.enum", Mode.Classic), Value("Express"), Value("express"), "did not decode as a Mode"),
    Case(
        Flag.of("rejects.of", Hours(0, 24), ::hours),
        structure("from" to Value(9), "to" to Value(17)),
        structure("from" to Value(9)),
        "did not decode",
    ),
)

/** Spec 0195: a flag says whether a raw value would read as its type, decoding as [Flag.value] does. */
object FlagRejectsSuite : Suite() {
    init {
        test("each factory passes a value it reads and names what is wrong with one it does not") {
            cases.forAll { case ->
                withClue(case.flag.key) {
                    case.flag.rejects(case.good) shouldBe null
                    (case.flag.rejects(case.bad) ?: "passed") shouldContain case.says
                }
            }
        }

        test("rejects passes exactly the values a provider holding them answers without an error code") {
            val values = cases.flatMap { case -> listOf(case.flag to case.good, case.flag to case.bad) }
            val api = OpenFeatureAPI.getInstance().apply { useSurefold() }
            values.withIndex().forAll { (index, pair) ->
                val (flag, value) = pair
                val domain = "rejects-$index"
                val held = MemoryFlag.builder<Any>().variant("on", value.held()).defaultVariant("on").build()
                api.setProviderAndWait(domain, InMemoryProvider(mapOf(flag.key to held)))

                val details = featureClient.locally(api.getClient(domain)) { flag.details() }

                withClue("${flag.key} holding $value") {
                    (flag.rejects(value) == null) shouldBe (details.errorCode == null)
                }
            }
        }

        test(
            "a whole number is an Int or a Long only inside its range, and a Long never past where a double holds it",
        ) {
            val limit = Flag.int("rejects.limit", 1)
            val total = Flag.long("rejects.total", 1L)

            limit.rejects(Value(5.0)) shouldBe null
            limit.rejects(Value(5.5)) shouldBe "5.5 is not a whole number"
            limit.rejects(Value(5_000_000_000L)) shouldBe "5000000000 is outside an Int's range"
            total.rejects(Value(9_007_199_254_740_991L)) shouldBe null
            total.rejects(Value(9_007_199_254_740_992L)) shouldBe "9007199254740992 is outside a Long's range"
        }
    }
}

/** What the in-memory provider holds for [this]: the raw value for a scalar, the [Value] itself for a structure. */
private fun Value.held(): Any = if (isStructure || isList) this else asObject()
