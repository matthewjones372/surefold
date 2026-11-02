package dev.surefold.app.flagd

import dev.openfeature.sdk.Client
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

private val limit = Flag.int("adoption.per-customer-limit", default = 1)

/** Spec 0184: flags in flagd's schema, written as a section of the service's own HOCON. */
// Every test starts flagd as the provider of OpenFeatureAPI, a process-wide singleton that one graph holds at a
// time, so two at once refuse each other's start: run concurrently, two of the three failed.
object FlagdFlagsSuite : Suite(concurrent = false) {
    init {
        test("a targeted flag in HOCON answers for the key evaluatedFor bound") {
            val graph = configOf(
                """
                flags {
                  "adoption.per-customer-limit" {
                    state = ENABLED
                    variants { standard = 3, raised = 5 }
                    defaultVariant = standard
                    targeting { "if" = [{ "==" = [{ "var" = "targetingKey" }, "ada"] }, "raised", "standard"] }
                  }
                }
                """.trimIndent(),
            ) + flagdFlags("flags")

            graph.use { _: Client ->
                evaluatedFor("ada") { limit.value() } to evaluatedFor("bob") { limit.value() }
            }.getOrNull().shouldNotBeNull { (ada, bob) ->
                withClue("flagd's in-process resolver evaluated the targeting against the bound key") {
                    ada shouldBe 5
                    bob shouldBe 3
                }
            }
        }

        test("a section flagd rejects refuses the start, naming the flag and where the section came from") {
            val graph = configOf(
                """
                flags {
                  "adoption.registry-check" { state = SOMETIMES, variants { on = true }, defaultVariant = on }
                }
                """.trimIndent(),
            ) + flagdFlags("flags")

            graph.use { _: Client -> }.leftOrNull().shouldNotBeNull { refused ->
                refused.shouldBeInstanceOf<StartupError.Refused> {
                    it.reason shouldContain "adoption.registry-check"
                    it.reason shouldContain "String"
                }
            }
        }

        test("a graph whose section is missing refuses the start, naming the path") {
            (configOf("other = 1") + flagdFlags("flags")).use { _: Client -> }.leftOrNull().shouldNotBeNull { refused ->
                refused.shouldBeInstanceOf<StartupError.Refused> { it.reason shouldContain "flags" }
            }
        }
    }
}
