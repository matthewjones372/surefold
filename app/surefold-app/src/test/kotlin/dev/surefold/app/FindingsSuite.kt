package dev.surefold.app

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldNotContain
import dev.surefold.junit.withClue
import kotlin.reflect.typeOf

private class Kettle
private class Cup
private class Saucer
private class Teapot

object FindingsSuite : Suite() {
    init {
        test("a key provided twice is a warning naming both sites") {
            val module = single<Kettle> { Kettle() } + single<Kettle> { Kettle() }

            val duplicate = module.findings().single()

            duplicate.severity shouldBe Severity.WARN
            duplicate.error.shouldBeInstanceOf<WiringError.Duplicate> { error ->
                error.key shouldBe typeOf<Kettle>()
                withClue("both the node that lost and the node that won are worth naming") {
                    error.shadowed shouldContain "FindingsSuite.kt"
                    error.wins shouldContain "FindingsSuite.kt"
                }
            }
        }

        test("an overriding is a duplicate on purpose and is not reported") {
            val module = single<Kettle> { Kettle() }.overriding(single<Kettle> { Kettle() })

            module.findings().shouldBeEmpty()
        }

        test("an overriding forgives its own collision and not one already there") {
            val careless = single<Kettle> { Kettle() } + single<Kettle> { Kettle() }

            val overridden = careless.overriding(single<Kettle> { Kettle() })

            withClue("the accidental duplicate in the base module is still worth saying") {
                overridden.findings().map { it.error }.filterIsInstance<WiringError.Duplicate>().size shouldBe 1
            }
        }

        test("a node no root reaches is a warning") {
            val module = single<Kettle> { Kettle() } +
                single { _: Kettle -> Cup() } +
                single<Saucer> { Saucer() }

            val finding = module.findings(root = typeOf<Cup>()).single()

            finding.severity shouldBe Severity.WARN
            finding.error.shouldBeInstanceOf<WiringError.Unreachable> { error ->
                error.key shouldBe typeOf<Saucer>()
                error.site shouldContain "FindingsSuite.kt"
            }
        }

        test("an unreachable module is named once rather than under everything it reaches") {
            val module = single<Kettle> { Kettle() } +
                single { _: Kettle -> Cup() } +
                single<Saucer> { Saucer() } +
                single { _: Saucer -> Teapot() }

            val unreached = module.findings(root = typeOf<Cup>())
                .map { it.error }
                .filterIsInstance<WiringError.Unreachable>()
                .map { it.key }

            withClue("Saucer is what the graph forgot; Teapot is only unreached because Saucer is") {
                unreached shouldContainExactly listOf(typeOf<Teapot>())
            }
        }

        test("a root nothing builds fails rather than making the whole graph unreachable") {
            val module = single<Kettle> { Kettle() }

            val findings = module.findings(root = typeOf<Cup>())

            findings.single().severity shouldBe Severity.FAIL
            findings.single().error shouldBe WiringError.NoRoot(typeOf<Cup>())
        }

        test("omitting the root skips the unreachable check and nothing else") {
            val module = single<Kettle> { Kettle() } + single<Saucer> { Saucer() }

            module.findings().shouldBeEmpty()
        }

        test("a missing key fails and is reported before a warning") {
            val module = single { _: Kettle -> Cup() } + single<Saucer> { Saucer() } + single<Saucer> { Saucer() }

            val findings = module.findings()

            findings.first().severity shouldBe Severity.FAIL
            findings.last().severity shouldBe Severity.WARN
        }

        test("the report names the severity and the places") {
            val module = single<Kettle> { Kettle() } + single<Kettle> { Kettle() } + single<Saucer> { Saucer() }

            val report = module.findings(root = typeOf<Kettle>()).report()

            report shouldContain "warning: Kettle provided twice"
            report shouldContain "shadowed"
            report shouldContain "wins"
            report shouldContain "nothing reaches Saucer"
            report shouldNotContain "error:"
        }
    }
}
