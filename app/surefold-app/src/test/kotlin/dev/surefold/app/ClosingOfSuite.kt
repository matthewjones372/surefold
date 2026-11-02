package dev.surefold.app

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withClue
import java.util.concurrent.CopyOnWriteArrayList

object ClosingOfSuite : Suite() {
    // Each test hands these a list of its own: the suite is one object, so a list in a field would be every test's.
    private class Pool(val closed: MutableList<String>) : AutoCloseable {
        override fun close() {
            closed += "pool"
        }
    }

    private class Client(val pool: Pool, val closed: MutableList<String>) : AutoCloseable {
        override fun close() {
            closed += "client"
        }
    }

    private class Broken : AutoCloseable {
        override fun close() = error("would not close")
    }

    init {
        test("a node built by closingOf is closed on release") {
            val closed = CopyOnWriteArrayList<String>()
            val app = closingOf { -> Pool(closed) }

            testApp(app) { _: Pool ->
                withClue("not while the block holds it") { closed shouldBe emptyList() }
            }

            closed shouldBe listOf("pool")
        }

        test("in reverse dependency order, so a client is closed before the pool it holds") {
            val closed = CopyOnWriteArrayList<String>()
            val app = closingOf { -> Pool(closed) } + closingOf { pool: Pool -> Client(pool, closed) }

            testApp(app) { _: Client -> }

            closed shouldBe listOf("client", "pool")
        }

        test("a close that throws is reported, and the nodes around it are closed anyway") {
            val closed = CopyOnWriteArrayList<String>()
            val app = closingOf { -> Pool(closed) } + closingOf { _: Pool -> Broken() }

            shouldThrow<IllegalStateException>({ testApp(app) { _: Broken -> } }) { thrown ->
                thrown.message shouldBe "would not close"
            }

            closed shouldBe listOf("pool")
        }
    }
}
