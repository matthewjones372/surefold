package dev.surefold.actor

import dev.surefold.capturingMetrics
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import dev.surefold.scope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** What one test's listeners heard, by name: made per test, since the suite's tests run together. */
private typealias HeardByName = ConcurrentHashMap<String, ConcurrentLinkedQueue<Int>>

object TopicSuite : Suite() {
    private fun listener(heard: HeardByName, name: String) =
        behaviour<Int, Unit>(Unit) { _, _, n ->
            stay().also { heard.computeIfAbsent(name) { ConcurrentLinkedQueue() } += n }
        }

    /** A sharded entity, a singleton or a group: a ref that is not an actor here and cannot be watched. */
    private class Elsewhere(val heard: ConcurrentLinkedQueue<Int>) : ActorRef<Int> {
        override val address = Address("", "/elsewhere", 0)

        override fun tell(message: Int) {
            heard += message
        }
    }

    init {
        test(
            "every subscriber hears every publish in order, and one that stops is dropped without a dead letter",
        ) {
            val heard = HeardByName()
            val letters = ConcurrentLinkedQueue<DeadLetter>()

            capturingMetrics { captured ->
                scope {
                    onDeadLetter(letters::add)
                    val prices = topic<Int>("prices")
                    val listeners = listOf("a", "b", "c").map { spawn(it, listener(heard, it)) }
                    listeners.forEach(prices::subscribe)
                    (1..50).forEach(prices::publish)
                    awaitIdle()

                    stop(listeners.last()).await()
                    awaitIdle()
                    (51..60).forEach(prices::publish)
                    prices.unsubscribe(listeners[1])
                    (61..70).forEach(prices::publish)
                    awaitIdle()
                    // Read before the scope closes: at close the last subscriber stops too, and the topic may drop it.
                    captured.gauge("surefold.topic.subscribers") shouldBe 1.0
                }

                heard.getValue("a").toList() shouldContainExactly (1..70).toList()
                heard.getValue("b").toList() shouldContainExactly (1..60).toList()
                heard.getValue("c").toList() shouldContainExactly (1..50).toList()
                letters.toList() shouldBe emptyList()
                captured.counter("surefold.topic.published") shouldBe 70.0
                captured.counter("surefold.topic.delivered") shouldBe (50.0 * 3 + 10 * 2 + 10)
            }
        }

        test("a publish is handed on, and a message that arrives is heard here and handed on no further") {
            val heard = HeardByName()
            val forwarded = ConcurrentLinkedQueue<Int>()

            scope {
                val prices = topic<Int>("prices") { forwarded += it }
                prices.subscribe(spawn("a", listener(heard, "a")))
                prices.publish(1)
                prices.ref.tell(TopicMessage.Arrive(2))
                awaitIdle()
            }

            heard.getValue("a").toList() shouldContainExactly listOf(1, 2)
            forwarded.toList() shouldContainExactly listOf(1)
        }

        test(
            "a subscriber that cannot be watched hears every publish until it unsubscribes, and the topic lives on",
        ) {
            val heard = HeardByName()
            val elsewhere = Elsewhere(ConcurrentLinkedQueue())

            scope {
                val prices = topic<Int>("prices")
                prices.subscribe(elsewhere)
                prices.subscribe(spawn("a", listener(heard, "a")))
                (1..3).forEach(prices::publish)
                awaitIdle()
                prices.unsubscribe(elsewhere)
                prices.publish(4)
                awaitIdle()
            }

            elsewhere.heard.toList() shouldContainExactly listOf(1, 2, 3)
            heard.getValue("a").toList() shouldContainExactly listOf(1, 2, 3, 4)
        }

        test(
            "a stalled subscriber stops nothing, the others hear everything, and what it cannot keep is counted full",
        ) {
            val heard = HeardByName()
            val open = CountDownLatch(1)
            val letters = ConcurrentLinkedQueue<DeadLetter>()
            val stalled = ConcurrentLinkedQueue<Int>()

            scope {
                onDeadLetter(letters::add)
                val prices = topic<Int>("prices")
                val slow =
                    spawn(
                        "slow",
                        behaviour<Int, Unit>(Unit) { _, _, n ->
                            stay().also {
                                if (n == 0) open.await() else stalled += n
                            }
                        },
                    )
                prices.subscribe(slow)
                val caughtUp = CountDownLatch(KEEP_AT_MOST + 1)
                prices.subscribe(
                    spawn(
                        "a",
                        behaviour<Int, Unit>(Unit) { _, _, n ->
                            stay().also {
                                heard.computeIfAbsent("a") { ConcurrentLinkedQueue() } += n
                                caughtUp.countDown()
                            }
                        },
                    ),
                )
                // All of them are more than a's mailbox and what is kept for it together, and a busy machine may not
                // run a until every publish is in; so a catches up halfway, or some of a's would be full too.
                (0..KEEP_AT_MOST).forEach(prices::publish)
                caughtUp.await(1, TimeUnit.MINUTES) shouldBe true
                (KEEP_AT_MOST + 1..KEEP_AT_MOST + 2_000).forEach(prices::publish)
                // The topic still takes subscribers and publishes while one of them is stalled.
                val late = LinkedBlockingQueue<Int>()
                prices.subscribe(spawn("b", behaviour<Int, Unit>(Unit) { _, _, n -> stay().also { late += n } }))
                prices.publish(-1)
                late.poll(1, TimeUnit.MINUTES) shouldBe -1
                open.countDown()
                awaitIdle()
            }

            val full = letters.filter { it.why == DeadLetter.Why.Full }
            heard.getValue("a").toList() shouldContainExactly (0..KEEP_AT_MOST + 2_000).toList() + -1
            full.map { it.recipient.path }.toSet() shouldBe setOf("/user/slow")
            (stalled.size + full.size) shouldBe KEEP_AT_MOST + 2_001
            stalled.toList() shouldBe stalled.sorted()
        }
    }
}
