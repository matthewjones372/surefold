package dev.surefold.idempotency

import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import dev.surefold.ratelimit.InMemoryRateLimits
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** docs/http-stores.md's example is GuideExample.kt, and it charges once. */
object HttpStoresGuideSuite : Suite() {
    private val marked = Regex("""<!-- http-stores -->\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)

    init {
        test("the guide's example is GuideExample.kt from its imports down") {
            val root = System.getProperty("surefold.idempotency.repoRoot")
            expect(root != null) { "the build must pass -Dsurefold.idempotency.repoRoot; see its build file" }
            val page = File(root!!, "docs/http-stores.md").readText()
            val fence = marked.findAll(page).map { it.groupValues[1] }.single()
            val source = File(root, EXAMPLE).readText()
            source.substring(source.indexOf("import ")).trim() shouldBe fence.trim()
        }

        test("a payment retried with its key is charged once, and the retry answers the first receipt") {
            val charges = AtomicInteger()
            val payments = Payments(PostgresIdempotency(IdempotencySuite.database()), InMemoryRateLimits())
            val charge = { "receipt-${charges.incrementAndGet()}" }
            payments.pay("acme", "c1", "k1", "40 GBP", charge) shouldBe Answer(201, "receipt-1")
            payments.pay("acme", "c1", "k1", "40 GBP", charge) shouldBe Answer(201, "receipt-1")
            payments.pay("acme", "c1", "k1", "90 GBP", charge) shouldBe
                Answer(422, "this key was used for another payment")
            charges.get() shouldBe 1
        }

        test("a charge that fails gives its key back, so the retry charges") {
            val payments = Payments(PostgresIdempotency(IdempotencySuite.database()), InMemoryRateLimits())
            shouldThrow<IllegalStateException> {
                payments.pay("acme", "c1", "k1", "40 GBP") { error("card declined") }
            }
            payments.pay("acme", "c1", "k1", "40 GBP") { "receipt" } shouldBe Answer(201, "receipt")
        }

        test("a client over its rate is told to come back") {
            // On a clock that stands still, so no token comes back while the twenty are taken.
            clock.locally(TestClock(Instant.parse("2026-10-27T12:00:00Z"))) {
                val payments = Payments(PostgresIdempotency(IdempotencySuite.database()), InMemoryRateLimits())
                repeat(20) { payments.pay("acme", "c1", "k$it", "1 GBP") { "r" } }
                payments.pay("acme", "c1", "k21", "1 GBP") { "r" }.status shouldBe 429
            }
        }
    }

    private const val EXAMPLE =
        "data/surefold-idempotency/src/test/kotlin/dev/surefold/idempotency/GuideExample.kt"
}
