package dev.surefold.junit.testkit.samples

import dev.surefold.junit.Suite
import dev.surefold.junit.ignore
import dev.surefold.junit.shouldBe

/** Tests that run one at a time, in the order written, so they finish in a known order. */
object OrderSuite : Suite(concurrent = false) {
    init {
        test("first") { 1 shouldBe 1 }
        test("second") { 2 shouldBe 2 }
        test("never runs", ignore("not yet")) { 3 shouldBe 3 }
    }
}
