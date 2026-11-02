package dev.surefold.arrow

import arrow.core.raise.either
import arrow.core.raise.ensure
import dev.surefold.fail.attempt
import dev.surefold.fail.left
import dev.surefold.fail.nonEmptyListOf
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import arrow.core.left as arrowLeft
import arrow.core.nonEmptyListOf as arrowNonEmptyListOf
import arrow.core.right as arrowRight

object ArrowSuite : Suite() {
    init {
        test("an Either keeps its side and value across the conversion and back") {
            1.right().toArrow() shouldBe 1.arrowRight()
            "e".left().toArrow() shouldBe "e".arrowLeft()
            1.arrowRight().toSurefold() shouldBe 1.right()
            "e".arrowLeft().toSurefold() shouldBe "e".left()
        }

        test("a NonEmptyList keeps its elements in order across the conversion and back") {
            nonEmptyListOf(1, 2, 3).toArrow() shouldBe arrowNonEmptyListOf(1, 2, 3)
            arrowNonEmptyListOf(1, 2, 3).toSurefold() shouldBe nonEmptyListOf(1, 2, 3)
            arrowNonEmptyListOf("only").toSurefold() shouldBe nonEmptyListOf("only")
        }

        test("a fail inside surefold raises into the Arrow scope around it") {
            either<String, Int> { surefold { 1 } } shouldBe 1.arrowRight()
            either<String, Int> { surefold { fail("surefold") } } shouldBe "surefold".arrowLeft()
        }

        test("a raise inside arrow fails the Surefold scope around it") {
            val raised = attempt<String, Int> {
                arrow {
                    ensure(false) { "arrow" }
                    0
                }
            }
            attempt<String, Int> { arrow { 2 } } shouldBe 2.right()
            raised shouldBe "arrow".left()
        }
    }
}
