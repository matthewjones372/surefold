package dev.surefold.app

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withClue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private class Prefs
private class Kept
private class Skipped
private class Ticker

object SubgraphSuite : Suite() {
    private fun graph(built: AtomicInteger) =
        single<Prefs> { Prefs() } +
            single { _: Prefs -> Kept() } +
            single<Ticker> { Ticker() } +
            single { _: Prefs -> built.incrementAndGet(); Skipped() }

    init {
        test("a subgraph builds only what its root needs") {
            val skipped = AtomicInteger()

            testApp(graph(skipped).subgraph<Kept>()) { kept: Kept -> kept }

            withClue("Skipped and Ticker are not on the way to Kept") {
                skipped.get() shouldBe 0
            }
        }

        test("overriding replaces a key everything below already points at") {
            val real = AtomicInteger()
            val module = (single<Prefs> { real.incrementAndGet(); Prefs() } + single { _: Prefs -> Kept() })
                .overriding(single<Prefs> { Prefs() })

            testApp(module) { _: Kept -> }

            withClue("the replaced recipe never runs, and Kept did not have to change") {
                real.get() shouldBe 0
            }
        }

        test("overriding a key nothing provides is refused") {
            shouldThrow<IllegalArgumentException>({
                single<Prefs> { Prefs() }.overriding(single<Ticker> { Ticker() })
            }) { failure -> failure.message.orEmpty() shouldContain "Ticker" }
        }

        test("testApp releases after the block fails") {
            val released = AtomicBoolean()
            val module = single<Prefs> { install({ Prefs() }) { _, _ -> released.set(true) } }

            shouldThrow<AssertionError>({
                testApp(module) { _: Prefs -> throw AssertionError("the assertion the test made") }
            })

            released.get() shouldBe true
        }

        test("testApp fails with what the start could not do") {
            shouldThrow<IllegalStateException>({
                testApp(single<Prefs> { Prefs() }) { _: Kept -> }
            }) { failure -> failure.message.orEmpty() shouldContain "Kept" }
        }
    }
}
