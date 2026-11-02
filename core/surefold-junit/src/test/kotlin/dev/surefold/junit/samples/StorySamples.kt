package dev.surefold.junit.samples

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.story
import dev.surefold.resource

/** Untitled stories, each test named for how its story should end. */
object StorySuite : SharedSuite<Int>() {
    override val shared: Resource<Int> = resource { 2 }

    init {
        test("passes its story") { two ->
            story {
                val four = When("a passing story doubles two") { two * 2 }
                Then("it has four") { four shouldBe 4 }
            }
        }
        test("fails two thens") { two ->
            story {
                Given("two") { }
                Then("it is three") { two shouldBe 3 }
                Then("it is four") { two shouldBe 4 }
            }
        }
        test("fails one then of two") { two ->
            story {
                Then("it is two") { two shouldBe 2 }
                Then("it is five") { two shouldBe 5 }
            }
        }
        test("throws in a step") { _ ->
            story {
                Given("a step that throws") { error("no database") }
            }
        }
    }
}
