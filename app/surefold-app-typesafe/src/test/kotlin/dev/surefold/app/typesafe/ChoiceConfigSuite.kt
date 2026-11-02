package dev.surefold.app.typesafe

import dev.surefold.app.Module
import dev.surefold.app.choice
import dev.surefold.app.single
import dev.surefold.app.testApp
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe

/** A choice reads its setting from the graph's own Config, so overriding one key flips it (spec 0136). */
object ChoiceConfigSuite : Suite() {
    private data class Bus(val kind: String)

    private val app: Module =
        configOf("bus.kind = in-process") +
            config<String>("bus") { string("kind") } +
            choice<String, Bus> { kind -> single<Bus> { Bus(kind) } }

    init {
        test("the branch follows the configuration") {
            testApp(app) { bus: Bus -> bus.kind } shouldBe "in-process"
        }

        test("overridingConfig flips the choice, with nothing reassembled") {
            testApp(app.overridingConfig("bus.kind = kafka")) { bus: Bus -> bus.kind } shouldBe "kafka"
        }
    }
}
