package io.github.matthewjones372.surefold.app.typesafe

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.choice
import io.github.matthewjones372.surefold.app.single
import io.github.matthewjones372.surefold.app.testApp

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
