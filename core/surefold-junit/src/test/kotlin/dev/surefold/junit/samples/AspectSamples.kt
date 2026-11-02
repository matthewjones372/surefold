package dev.surefold.junit.samples

import dev.surefold.junit.Aspect
import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.ignore
import dev.surefold.junit.tag
import java.util.concurrent.CopyOnWriteArrayList

/** Tagged as a whole, so each of its tests is. */
object TaggedSuite : Suite(aspects = tag("io")) {
    init {
        test("reads the wire") { expect(true) }
        test("writes the wire") { expect(true) }
    }
}

/** One test tagged on top of the suite's own tag, and one with only the suite's. */
object PartlyTaggedSuite : Suite(aspects = tag("suite")) {
    init {
        test("tagged", tag("io")) { expect(true) }
        test("untagged") { expect(true) }
    }
}

/** Two ignored tests that would fail if they ran, and one that runs. */
object IgnoringSuite : Suite() {
    val ran: MutableList<String> = CopyOnWriteArrayList()

    init {
        test("speaks HTTP/3", ignore("no QUIC on CI")) {
            ran += "speaks HTTP/3"
            expect(false)
        }
        test("ignored by the outer side", tag("io") + ignore("outer") + ignore("inner")) {
            ran += "ignored by the outer side"
            expect(false)
        }
        test("passes") { expect(ran.add("passes")) }
    }
}

/** Records the order its suite's aspect and its test's two aspects are entered and left in. */
object WrappedSuite : Suite(aspects = recording("suite")) {
    val seen: MutableList<String> = recorded

    init {
        test("wrapped", recording("left") + recording("right")) { expect(recorded.add("test")) }
    }
}

private val recorded: MutableList<String> = CopyOnWriteArrayList()

private fun recording(name: String) = Aspect.around { test ->
    recorded += "$name in"
    test()
    recorded += "$name out"
}
