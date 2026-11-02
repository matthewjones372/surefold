package dev.surefold.otel

import dev.surefold.Shared
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.transact
import io.opentelemetry.api.common.AttributeKey

private const val RETRIES = 3

// Commits a change to [shared] from another thread, so the run that read it cannot commit.
private fun <T> changeElsewhere(shared: Shared<T>, change: (T) -> T) =
    Thread.ofVirtual().start { transact { shared.update(change) } }.join()

/** Spec 0212: a step that had to run again says so on the span it ran in, once, when it commits. */
object RetriedStepSuite : Suite() {
    init {

        test("a step that ran again three times adds one event with four attempts and its causes") {
            val exported = Spans()
            val tracer = exported.tracer
            val stalls = Shared(10, name = "free.stalls")
            var runs = 0

            tracer.span("checkout") {
                transact("hold") {
                    val left = stalls.get()
                    if (++runs <= RETRIES) changeElsewhere(stalls) { it + 1 }
                    stalls.set(left - 1)
                }
            }

            val event = exported.all().single { it.name == "checkout" }.events.single()
            event.name shouldBe "transact.retried"
            event.attributes.get(AttributeKey.stringKey("transact")) shouldBe "hold"
            event.attributes.get(AttributeKey.longKey("attempts")) shouldBe 4L
            event.attributes.get(AttributeKey.longKey("retries.free.stalls")) shouldBe 3L
            event.attributes.get(AttributeKey.booleanKey("solo")) shouldBe null
        }

        test("a step that ran alone in the end says so on its event") {
            val exported = Spans()
            val tracer = exported.tracer
            val stalls = Shared(10, name = "free.stalls")
            var runs = 0

            tracer.span("checkout") {
                // The second run is alone, and changes nothing elsewhere: alone, it would wait on that change.
                transact("hold", soloAfter = 1) {
                    val left = stalls.get()
                    if (++runs == 1) changeElsewhere(stalls) { it + 1 }
                    stalls.set(left - 1)
                }
            }

            val event = exported.all().single { it.name == "checkout" }.events.single()
            event.attributes.get(AttributeKey.longKey("attempts")) shouldBe 2L
            event.attributes.get(AttributeKey.booleanKey("solo")) shouldBe true
        }

        test("a step that commits on its first run adds nothing to the span") {
            val exported = Spans()
            val tracer = exported.tracer
            val free = Shared(10)

            tracer.span("checkout") { transact("hold") { free.update { it - 1 } } }

            exported.all().single { it.name == "checkout" }.events shouldBe emptyList()
        }
    }
}
