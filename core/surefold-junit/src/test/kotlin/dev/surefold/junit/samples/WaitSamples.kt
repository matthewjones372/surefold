package dev.surefold.junit.samples

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.story
import dev.surefold.test.eventually
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Checks waited on until they hold, as a projection catching up is. */
object WaitSuite : Suite() {
    init {
        test("holds once its check does, on the third try") {
            val reads = AtomicInteger(0)
            eventually(1.seconds, every = 1.milliseconds) { reads.incrementAndGet() shouldBe 3 }
        }
        test("gives up on a check that never holds") {
            eventually(50.milliseconds, every = 10.milliseconds) { "Adopting" shouldBe "Adopted" }
        }
        test("gives up on a value, answering the last try's") {
            val reads = AtomicInteger(0)
            val read: Int =
                eventually(50.milliseconds, every = 10.milliseconds) {
                    reads.incrementAndGet().also { it shouldBe 0 }
                }
            read shouldBe reads.get()
            read shouldBe 0
        }
        test("gives up as a story's step, and the story goes on") {
            story {
                val stage =
                    Then("it is adopted").eventually(50.milliseconds, every = 10.milliseconds) {
                        "Adopting".also { it shouldBe "Adopted" }
                    }
                And("the last read is answered") { stage shouldBe "Adopting" }
            }
        }
        test("waits as a story's step") {
            val reads = AtomicInteger(0)
            story {
                Then("the third read sees it").eventually(1.seconds, every = 1.milliseconds) {
                    reads.incrementAndGet() shouldBe 3
                }
            }
        }
        test("tries again after a throw") {
            val reads = AtomicInteger(0)
            eventually(1.seconds, every = 1.milliseconds) {
                check(reads.incrementAndGet() > 1) { "the server is still starting" }
                reads.get() shouldBe 2
            }
        }
    }
}
