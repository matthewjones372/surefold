package dev.surefold.cluster

import dev.surefold.actor.DeadLetter
import dev.surefold.actor.behaviour
import dev.surefold.actor.onDeadLetter
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.freePort
import dev.surefold.actor.remote.node
import dev.surefold.actor.stay
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import dev.surefold.scope
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private val unhurried =
    Gossiping(probeEvery = 500.milliseconds, ackWithin = 250.milliseconds, formAfter = 1_000.milliseconds)

object BusySingletonSuite : Suite() {
    init {
        test("a burst to a busy singleton is kept in order by its region, which goes on") {
            val port = freePort()
            val heard = LinkedBlockingQueue<Int>()
            val open = CountDownLatch(1)
            val letters = ConcurrentLinkedQueue<DeadLetter>()

            scope {
                onDeadLetter(letters::add)
                val seeds = Discovery.static(Node("", "127.0.0.1", port))
                val cluster = cluster(node("solo", port), seeds, unhurried, leaveWithin = Duration.ZERO)
                cluster.await(1.minutes) { v -> v.members.any { it.status == Status.Up } } shouldBe true
                // Its first message holds its step until the burst is all sent, so its mailbox fills behind it.
                val clock =
                    cluster.singleton("clock", Codecs.int) {
                        behaviour<Int, Unit>(Unit) { _, _, n ->
                            stay().also { if (n == 0) open.await() else heard += n }
                        }
                    }
                clock.tell(0)
                (1..5_000).forEach(clock::tell)
                open.countDown()

                List(5_000) { heard.poll(1, TimeUnit.MINUTES) } shouldContainExactly (1..5_000).toList()
                clock.tell(5_001)
                heard.poll(1, TimeUnit.MINUTES) shouldBe 5_001
            }

            letters.filter { it.why != DeadLetter.Why.Unreachable } shouldBe emptyList()
        }
    }
}
