package io.github.matthewjones372.surefold.app

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.withClue

private class Knob
private class Pool
private class Repo
private class Cache
private class Metrics
private class Server

object ModuleSuite : Suite() {
    init {
        test("render draws an edge for every dependency") {
            val rendered = (single<Knob> { Knob() } + single { _: Knob -> Pool() }).render()

            rendered shouldContain "graph TD"
            rendered shouldContain "Knob --> Pool"
        }

        test("a later module replaces an earlier node under the same key") {
            val module = single<Knob> { Knob() } + single<Knob> { Knob() } + single { _: Knob -> Pool() }

            val declarations = module.render().lines().filter { it.contains("Knob[") }

            withClue("both recipes build a Knob, so the graph holds one node and one edge into Pool") {
                declarations.size shouldBe 1
            }
            module.render() shouldContain "Knob --> Pool"
        }

        test("a recipe declares up to five dependencies as parameters") {
            val module = single<Knob> { Knob() } +
                single { _: Knob -> Pool() } +
                single { _: Knob, _: Pool -> Repo() } +
                single { _: Knob, _: Pool, _: Repo -> Cache() } +
                single { _: Knob, _: Pool, _: Repo, _: Cache -> Metrics() } +
                single { _: Knob, _: Pool, _: Repo, _: Cache, _: Metrics -> Server() }

            val rendered = module.render()

            rendered.lines().count { it.contains("[") } shouldBe 6
            rendered shouldContain "Metrics --> Server"
            rendered shouldContain "Knob --> Server"
        }

        test("a dependency nothing builds is still drawn") {
            val rendered = single { _: Knob -> Pool() }.render()

            withClue("the edge is the reader's clue that Knob is the key nothing provides") {
                rendered shouldContain "Knob --> Pool"
            }
        }
    }
}
