package dev.surefold.app

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldStartWith
import dev.surefold.junit.withClue
import java.io.File
import kotlin.reflect.typeOf

private class Hammer
private class Nail

/** Where this file's own sources are, which is what a build hands the checker. */
private val sources = listOf(File("src/test/kotlin"))

object DiagnosticsSuite : Suite() {
    init {
        test("a missing dependency is a compiler error pointing at the line that asked") {
            val line = single { _: Hammer -> Nail() }.findings().diagnostics(sources)

            line shouldStartWith "e: file://"
            line shouldContain "DiagnosticsSuite.kt:"
            line shouldContain "Nail needs Hammer, and nothing builds it"
            withClue("a diagnostic that wraps is two diagnostics to whatever parses the output") {
                line.lines().size shouldBe 1
            }
        }

        test("the file in the URI is one that exists, so the IDE has something to open") {
            val line = single { _: Hammer -> Nail() }.findings().diagnostics(sources)

            // `file://<path>:<line>:<column> <message>`, and the message has colons of its own.
            val located = line.substringAfter("file://").substringBefore(" ")
            val path = located.substringBeforeLast(":").substringBeforeLast(":")

            File(path).isFile shouldBe true
        }

        test("a fault with no line of its own still gets a diagnostic") {
            val loop = single { _: Nail -> Hammer() } + single { _: Hammer -> Nail() }

            val line = loop.findings().diagnostics(sources)

            withClue("a cycle is written on as many lines as it has nodes, so it names none of them") {
                (line shouldStartWith "e: surefold-app: cycle ")
            }
        }

        test("an unreached node is a warning rather than an error") {
            val module = singleOf(::Hammer) + singleOf(::Nail)

            val line = module.findings(typeOf<Nail>()).diagnostics(sources)

            line shouldStartWith "w: file://"
            line shouldContain "nothing reaches Hammer"
        }

        test("a key the graph builds under a name that reads the same is named beside the missing one") {
            val fault = Finding(Severity.FAIL, WiringError.Missing(typeOf<Hammer>(), typeOf<Nail>()))

            val line = listOf(fault).diagnostics(sources, setOf(typeOf<Hammer?>()))

            withClue("nothing builds Hammer, with a Hammer? in the graph, is the least helpful true thing") {
                line shouldContain "which is not the same type"
            }
        }

        test("without source directories there is nothing to link and the sentence still says it") {
            val line = single { _: Hammer -> Nail() }.findings().diagnostics(emptyList())

            line shouldBe "e: surefold-app: Nail needs Hammer, and nothing builds it"
        }
    }
}
