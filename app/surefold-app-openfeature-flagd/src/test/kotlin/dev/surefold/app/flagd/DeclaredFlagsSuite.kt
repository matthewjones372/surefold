package dev.surefold.app.flagd

import dev.openfeature.sdk.Client
import dev.openfeature.sdk.Value
import dev.surefold.app.StartupError
import dev.surefold.app.typesafe.configOf
import dev.surefold.app.use
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.withClue
import dev.surefold.openfeature.Flag
import dev.surefold.openfeature.evaluatedFor
import kotlin.time.Duration.Companion.seconds

private enum class Checkout { Classic, Express }

private data class Hours(val from: Int, val to: Int)

private object Declared {
    val limit = Flag.int("adoption.per-customer-limit", default = 1)
    val mode = Flag.enum("checkout.mode", default = Checkout.Classic)
    val timeout = Flag.duration("checkout.registry-timeout", default = 2.seconds)
    val hours = Flag.of("checkout.hours", default = Hours(0, 24)) { value: Value ->
        value.asStructure().let { Hours(it.getValue("from").asInteger(), it.getValue("to").asInteger()) }
    }
    val all = listOf(limit, mode, timeout, hours)
}

private val matching = """
    flags {
      "adoption.per-customer-limit" {
        state = ENABLED
        variants { standard = 3, raised = 5 }
        defaultVariant = standard
        targeting { "if" = [{ "==" = [{ "var" = "targetingKey" }, "ada"] }, "raised", "standard"] }
      }
      "checkout.mode" { state = ENABLED, variants { old = Classic, new = Express }, defaultVariant = new }
      "checkout.registry-timeout" { state = ENABLED, variants { short = "1s", long = "5s" }, defaultVariant = long }
      "checkout.hours" { state = ENABLED, variants { day { from = 9, to = 17 } }, defaultVariant = day }
    }
""".trimIndent()

private inline fun refusal(conf: String, declared: List<Flag<*>>, check: (String) -> Unit) =
    (configOf(conf) + flagdFlags("flags", declared)).use { _: Client -> }
        .leftOrNull()
        .shouldNotBeNull { error -> error.shouldBeInstanceOf<StartupError.Refused> { check(it.reason) } }

/** Spec 0195: the flags a service declares, checked against the section's variants before the provider starts. */
// Every test starts flagd as the provider of OpenFeatureAPI, a process-wide singleton that one graph holds at a
// time, so two at once refuse each other's start: run concurrently, three of the four failed.
object DeclaredFlagsSuite : Suite(concurrent = false) {
    init {
        test("a section that matches its declarations starts, and its flags answer as before") {
            (configOf(matching) + flagdFlags("flags", Declared.all)).use { _: Client ->
                listOf(
                    evaluatedFor("ada") { Declared.limit.value() },
                    evaluatedFor("bob") { Declared.limit.value() },
                    evaluatedFor("bob") { Declared.mode.value() },
                    evaluatedFor("bob") { Declared.hours.value() },
                )
            }.getOrNull().shouldNotBeNull { (ada, bob, mode, hours) ->
                ada shouldBe 5
                bob shouldBe 3
                mode shouldBe Checkout.Express
                hours shouldBe Hours(9, 17)
            }
        }

        test(
            "a fractional Int, a constant that does not exist and a missing key are all named, with their lines",
        ) {
            refusal(
                """
                flags {
                  "adoption.per-customer-limit" {
                    state = ENABLED
                    variants { standard = 3, raised = 5.5 }
                    defaultVariant = standard
                  }
                  "checkout.mode" { state = ENABLED, variants { old = Classic, new = Fast }, defaultVariant = old }
                  "checkout.hours" { state = ENABLED, variants { day { from = 9, to = 17 } }, defaultVariant = day }
                }
                """.trimIndent(),
                Declared.all,
            ) { reason ->
                withClue(reason) {
                    reason shouldContain "flags at flags do not match their declarations"
                    reason shouldContain
                        "adoption.per-customer-limit: variant raised 5.5 is not a whole number (String: 4)"
                    reason shouldContain "checkout.mode: variant new \"Fast\" did not decode as a Checkout"
                    reason shouldContain "(String: 7)"
                    reason shouldContain "checkout.registry-timeout: declared but not in the section"
                    reason.lines().size shouldBe 4
                }
            }
        }

        test(
            "a whole number too big for an Int is refused for a numeric flag, since flagd reads it as no number",
        ) {
            val total = Flag.long("checkout.total", default = 1L)
            val section =
                """flags { "checkout.total" { state = ENABLED, variants { big = 5000000000 }, defaultVariant = big } }"""

            refusal(section, listOf(total)) { reason ->
                reason shouldContain "checkout.total: variant big 5000000000 is too big for flagd to read as a number"
            }
        }

        test("without declarations nothing is checked beyond flagd's own schema") {
            val graph = configOf(
                """
                flags {
                  "adoption.per-customer-limit" { state = ENABLED, variants { on = 5.5 }, defaultVariant = on }
                }
                """.trimIndent(),
            ) + flagdFlags("flags")

            graph.use { _: Client -> Declared.limit.value() }.getOrNull() shouldBe 5
        }
    }
}
