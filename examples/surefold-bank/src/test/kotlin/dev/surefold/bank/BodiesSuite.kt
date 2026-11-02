package dev.surefold.bank

import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe

object BodiesSuite : Suite() {
    init {
        test(
            "a body is read with fields it does not know ignored, and anything that is not an object is not a body",
        ) {
            decoded<CrashBody>("""{"node":"n2","extra":[1]}""") shouldBe CrashBody("n2")
            val broken =
                listOf("", "[]", "{", """{"a"}""", """{"a":}""", """{"a":1,}""", """{"a":1} x""", """{"a":"\q"}""")
            broken.forAll { decoded<CrashBody>(it) shouldBe null }
        }

        test("a missing field still reads, so its route can say what is wrong") {
            decoded<OpenBody>("{}") shouldBe OpenBody()
            decoded<CrashBody>("{}") shouldBe CrashBody("")
            decoded<LoadBody>("{}")?.on.flag() shouldBe null
        }

        test("an amount is a whole number written bare or as a string, and nothing else") {
            fun amount(written: String) = decoded<TransferBody>("""{"amount":$written}""")?.amount.whole()
            amount("12") shouldBe 12L
            amount("\"12\"") shouldBe 12L
            amount("-3") shouldBe -3L
            listOf("1.5", "\"x\"", "null", "true").forAll { amount(it) shouldBe null }
            decoded<TransferBody>("""{"amount":{}}""") shouldBe null
        }

        test("on is true or false written bare or as a string") {
            fun on(written: String) = decoded<LoadBody>("""{"on":$written}""")?.on.flag()
            on("true") shouldBe true
            on("\"false\"") shouldBe false
            listOf("1", "\"yes\"", "null").forAll { on(it) shouldBe null }
        }
    }
}
