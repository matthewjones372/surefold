package dev.surefold.junit.testkit.samples

import dev.surefold.junit.Suite
import dev.surefold.junit.ignore
import dev.surefold.junit.shouldBe
import dev.surefold.junit.tag

/** A test that ends each way, for the matchers to read. */
object OutcomesSuite : Suite() {
    init {
        test("passes", tag("fast")) { 2 shouldBe 2 }
        test("also passes") { 3 shouldBe 3 }
        test("fails a check") { 2 shouldBe 3 }
        test("throws") { error("no database") }
        test("is skipped", ignore("not yet")) { 2 shouldBe 2 }
    }
}
