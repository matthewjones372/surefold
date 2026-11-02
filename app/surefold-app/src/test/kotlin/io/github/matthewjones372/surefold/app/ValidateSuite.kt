package io.github.matthewjones372.surefold.app

import dev.surefold.fail.nonEmptyListOf
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.shouldNotContain
import dev.surefold.junit.withClue
import kotlin.reflect.typeOf

private class Settings
private class Pipe
private class Store
private class Buffer

object ValidateSuite : Suite() {
    init {
        test("a graph with no edges plans as one layer") {
            (single<Settings> { Settings() } + single<Buffer> { Buffer() })
                .validate().getOrNull().shouldNotBeNull { plan -> plan.layers.size shouldBe 1 }
        }

        test("a dependency is planned before what needs it") {
            val module = single<Settings> { Settings() } +
                single { _: Settings -> Pipe() } +
                single { _: Pipe -> Store() }

            module.validate().getOrNull().shouldNotBeNull { plan ->
                plan.layers shouldContainExactly listOf(
                    listOf(typeOf<Settings>()),
                    listOf(typeOf<Pipe>()),
                    listOf(typeOf<Store>()),
                )
            }
        }

        test("independent nodes share a layer") {
            val module = single<Settings> { Settings() } +
                single { _: Settings -> Pipe() } +
                single { _: Settings -> Buffer() }

            module.validate().getOrNull().shouldNotBeNull { plan ->
                withClue("Pipe and Buffer each need only Settings, so neither waits for the other") {
                    plan.layers[1].toSet() shouldBe setOf(typeOf<Pipe>(), typeOf<Buffer>())
                }
            }
        }

        test("validate runs no recipe body") {
            val module = single<Settings> { Settings() } +
                single<Pipe, Settings> { error("a recipe must not run to be validated") }

            module.validate().getOrNull().shouldNotBeNull()
        }

        test("a missing dependency is named with what needed it") {
            single { _: Settings -> Pipe() }.validate().leftOrNull().shouldNotBeNull { errors ->
                errors.head.shouldBeInstanceOf<WiringError.Missing> { missing ->
                    missing.copy(site = null) shouldBe
                        WiringError.Missing(key = typeOf<Settings>(), neededBy = typeOf<Pipe>())
                }
            }
        }

        test("a cycle names the path around it") {
            val module = single { _: Store -> Pipe() } + single { _: Pipe -> Store() }

            module.validate().leftOrNull().shouldNotBeNull { errors ->
                errors.head.shouldBeInstanceOf<WiringError.Cycle> { cycle ->
                    val path = cycle.path
                    withClue("the path ends where it began") {
                        path.first() shouldBe path.last()
                        path.toSet() shouldBe setOf(typeOf<Pipe>(), typeOf<Store>())
                    }
                }
            }
        }

        test("the report names every consumer of a missing key") {
            val module = single { _: Settings -> Pipe() } + single { _: Settings -> Buffer() }

            module.validate().leftOrNull().shouldNotBeNull { errors ->
                val report = errors.report()
                report shouldContain "❯ missing Settings"
                report shouldContain "❯     for Pipe"
                report shouldContain "❯     for Buffer"
            }
        }
    }
}

private class Sdk

/**
 * A Java factory hands back a platform type, so the key is `Thing!` and nothing asking for a `Thing`
 * matches it. "missing Thing" with a Thing in the graph is the least helpful true sentence a build
 * can print, so the report names the one that reads the same.
 */
object AlikeKeySuite : Suite() {
    init {
        test("a key that reads the same as a missing one is named beside it") {
            val faults = nonEmptyListOf(WiringError.Missing(typeOf<Settings>(), typeOf<Pipe>()))

            val said = faults.report(setOf(typeOf<Settings?>(), typeOf<Sdk>()))

            withClue("the graph held a Settings? and the reader was told so") {
                said shouldContain "missing Settings"
                said shouldContain "which is not the same type"
            }
        }

        test("nothing alike is said where nothing is alike") {
            val faults = nonEmptyListOf(WiringError.Missing(typeOf<Settings>(), typeOf<Pipe>()))

            faults.report(setOf(typeOf<Sdk>())) shouldNotContain "not the same type"
        }
    }
}
