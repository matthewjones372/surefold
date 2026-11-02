package dev.surefold.app.kotlinx

import dev.surefold.app.StartupError
import dev.surefold.app.testApp
import dev.surefold.app.typesafe.configOf
import dev.surefold.app.use
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldNotBeNull
import kotlinx.serialization.Serializable

private enum class Mode { Fast, Safe }

@Serializable
private data class Moded(val mode: Mode, val fallback: Mode)

object EnumConfigSuite : Suite() {
    init {
        test("an enum reads its entry by name") {
            val file = configOf("petshop { mode = Safe, fallback = Fast }")

            testApp(file + config<Moded>("petshop")) { moded: Moded -> moded } shouldBe Moded(Mode.Safe, Mode.Fast)
        }

        test("an enum that is none of its entries is refused naming the entries it could have been") {
            val file = configOf("petshop { mode = Reckless, fallback = Slow }")

            (file + config<Moded>("petshop")).use { _: Moded -> }.leftOrNull().shouldNotBeNull { error ->
                error.shouldBeInstanceOf<StartupError.Refused> { refused ->
                    refused.reason shouldContain "Fast or Safe, not Reckless"
                    refused.reason shouldContain "Fast or Safe, not Slow"
                }
            }
        }
    }
}
