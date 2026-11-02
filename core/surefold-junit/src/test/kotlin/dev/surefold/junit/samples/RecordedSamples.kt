package dev.surefold.junit.samples

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBe
import dev.surefold.junit.story
import dev.surefold.scope
import java.util.concurrent.atomic.AtomicReference

/** Calls the check it is handed on each of [values], as a statement. */
private fun onEach(vararg values: Int, check: (Int) -> Unit) {
    values.forEach { check(it) }
}

/** Checks recorded as they are made, wherever in the test they are made. */
object RecordedSuite : Suite() {
    init {
        test("records each check written as a statement") {
            2 shouldBe 3
            2 shouldBe 2
            expect(2 + 2 == 5)
        }
        test("fails a test that checks nothing") {
            println("nothing checked")
        }
        test("counts a story step left out of any value") {
            story {
                Then("it is three") { 2 shouldBe 3 }
                Then("it is two") { 2 shouldBe 2 }
            }
        }
        test("records each check a helper calls with the block it is handed") {
            onEach(2, 3) { it shouldBe 3 }
        }
        test("records a check handed to the body's last line") {
            val check = { 2 shouldBe 3 }
            check()
        }
        test("records a check made on a fork") {
            scope { async { 2 shouldBe 4 } }
        }
        test("throws a check made on a thread surefold does not carry, there") {
            val thrown = AtomicReference<Throwable>()
            val bare = Thread {
                try {
                    2 shouldBe 2
                } catch (refused: IllegalStateException) {
                    thrown.set(refused)
                }
            }
            bare.start()
            bare.join()
            expect(thrown.get() is IllegalStateException)
        }
    }
}
