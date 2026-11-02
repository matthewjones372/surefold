package dev.surefold.app.cluster

import dev.surefold.actor.DeadLetter
import dev.surefold.actor.behaviour
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.stay
import dev.surefold.app.Module
import dev.surefold.app.actor.actors
import dev.surefold.app.single
import dev.surefold.app.testApp
import dev.surefold.app.typesafe.configOf
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.Sharded
import dev.surefold.cluster.Status
import dev.surefold.cluster.sharding
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.shouldBeGreaterThan
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes

/** A node's cluster and its kind "ledger", whose entities count what they are told. */
private class Ledgers(val cluster: Cluster, val ledgers: Sharded<Int>)

private fun node(
    name: String,
    port: Int,
    seeds: List<Int>,
    heard: AtomicInteger,
    letters: MutableCollection<DeadLetter>,
): Module {
    val hocon = """
        surefold.cluster {
          node { name = $name, port = $port }
          join = static
          static.seeds = [${seeds.joinToString { "\"127.0.0.1:$it\"" }}]
          gossip { probeEvery = 200ms, ackWithin = 60ms, formAfter = 1s }
          whenDowned = stay
        }
    """.trimIndent()
    return configOf(hocon) + actors(onDeadLetter = { letters += it }) + cluster("surefold.cluster") +
        single { cluster: Cluster ->
            val counting = behaviour<Int, Unit>(Unit) { _, _, _ -> stay().also { heard.incrementAndGet() } }
            Ledgers(cluster, cluster.sharding("ledger", Codecs.int, 10.minutes) { _ -> counting })
        }
}

/** Runs [module] on a thread of its own, handing its [Ledgers] to [started], until [done]. */
private fun running(module: Module, started: AtomicReference<Ledgers>, done: CountDownLatch) =
    Thread.ofPlatform().start {
        testApp(module) { ledgers: Ledgers ->
            started.set(ledgers)
            done.await(2, TimeUnit.MINUTES)
        }
    }

private fun AtomicReference<Ledgers>.awaitUp(count: Int): Ledgers {
    while (get()?.cluster?.view?.members?.count { it.status == Status.Up } != count) Thread.sleep(50)
    return get()
}

/** Spec 0129: a node that joins through surefold-app is given no shard before its regions are open. */
object JoiningRegionSuite : Suite() {
    init {
        test("a node that joins while its future entities are being told has no dead letter for their region") {
            val ports = List(3) { ServerSocket(0).use { socket -> socket.localPort } }.sorted()
            val done = CountDownLatch(1)
            val ignored = ConcurrentLinkedQueue<DeadLetter>()
            val (first, second, third) = List(3) { AtomicReference<Ledgers>() }
            val onThird = AtomicInteger()
            val lettersOnThird = ConcurrentLinkedQueue<DeadLetter>()
            val threads = mutableListOf(
                running(node("a", ports[0], ports, AtomicInteger(), ignored), first, done),
                running(node("b", ports[1], ports, AtomicInteger(), ignored), second, done),
            )
            val telling = AtomicBoolean(true)
            try {
                val sender = first.awaitUp(2)
                second.awaitUp(2)
                // Told the whole time the third node joins, to more ids than there are shards, so some are its own.
                val teller = Thread.ofPlatform().start {
                    var n = 0
                    while (telling.get()) {
                        sender.ledgers.entity("l-${n % 500}").tell(n++)
                        Thread.sleep(1)
                    }
                }
                threads += running(node("c", ports[2], ports, onThird, lettersOnThird), third, done)
                third.awaitUp(3)
                Thread.sleep(2_000)
                telling.set(false)
                teller.join()

                lettersOnThird.filter { it.recipient.path.startsWith("/user/sharding-ledger") }.shouldBeEmpty()
                onThird.get() shouldBeGreaterThan 0
            } finally {
                telling.set(false)
                done.countDown()
                threads.forEach(Thread::join)
            }
        }
    }
}
