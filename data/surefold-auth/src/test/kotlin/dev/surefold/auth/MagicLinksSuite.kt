package dev.surefold.auth

import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.minutes

/** Links that redeem once, however many try at once, and not after they expire (spec 0227). */
object MagicLinksSuite : Suite() {
    private val user = UserId("u-1")

    init {
        test("a link redeems to its user once, and to nothing after") {
            val links = PostgresMagicLinks(SessionsSuite.database())
            val link = links.issue(user)
            links.redeem(link) shouldBe user
            links.redeem(link) shouldBe null
            links.redeem(Tokens.issue()) shouldBe null
        }

        test("twenty threads redeeming one link at once get it once between them") {
            val links = PostgresMagicLinks(SessionsSuite.database())
            val link = links.issue(user)
            val redeemed = ConcurrentLinkedQueue<UserId>()
            val ready = CountDownLatch(20)
            (1..20).map {
                Thread.ofVirtual().start {
                    ready.countDown()
                    ready.await()
                    links.redeem(link)?.let(redeemed::add)
                }
            }.forEach { it.join() }
            redeemed.toList() shouldBe listOf(user)
        }

        test("a link past its time redeems to nothing, and prune clears the expired") {
            val time = TestClock(Instant.parse("2026-10-27T12:00:00Z"))
            clock.locally(time) {
                val links = PostgresMagicLinks(SessionsSuite.database(), ttl = 15.minutes)
                val late = links.issue(user)
                val pruned = links.issue(user)
                time.adjust(10.minutes)
                val fresh = links.issue(user)
                time.adjust(6.minutes)
                links.redeem(late) shouldBe null
                links.prune() shouldBe 1
                links.redeem(fresh) shouldBe user
                links.redeem(pruned) shouldBe null
            }
        }
    }
}
