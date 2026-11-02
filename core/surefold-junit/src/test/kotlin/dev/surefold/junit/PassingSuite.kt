package dev.surefold.junit

import dev.surefold.Resource
import dev.surefold.resource

/** Run by this module's own `test` task, so its tests appear in Gradle's JUnit XML beside Jupiter's. */
object PassingSuite : SharedSuite<Int>() {
    override val shared: Resource<Int> = resource { 2 }

    init {
        test("the shared value reaches the test") { two -> expect(two == 2) }
        test("a test whose expectation holds passes") { expect(true) }
    }
}
