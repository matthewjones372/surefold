package dev.surefold.cluster

import dev.surefold.actor.ActorRef
import dev.surefold.actor.Reply
import dev.surefold.actor.Signal
import dev.surefold.actor.ask
import dev.surefold.actor.behaviour
import dev.surefold.actor.onSignal
import dev.surefold.actor.onStart
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.remote.freePort
import dev.surefold.actor.remote.node
import dev.surefold.actor.stay
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import dev.surefold.scope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Asks the clock which node it runs on. */
private data class Where(val reply: Reply<String>)

private val whereCodec = object : MessageCodec<Where> {
    override fun write(message: Where, out: WireOut) = out.reply(message.reply, Codecs.string)

    override fun read(input: WireIn) = Where(input.reply(Codecs.string))
}

private val clocksRunning = AtomicInteger()
private val clocksTwice = AtomicBoolean(false)
private val clockStarted = CountDownLatch(1)

private fun clock(on: String) = behaviour<Where, Unit>(Unit) { _, _, where -> stay().also { where.reply.invoke(on) } }
    .onStart {
        if (clocksRunning.incrementAndGet() > 1) clocksTwice.set(true)
        clockStarted.countDown()
    }.onSignal { _, _, signal ->
        if (signal == Signal.Stopping) clocksRunning.decrementAndGet()
        stay()
    }

private val lively =
    Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 1_000.milliseconds)

/** A node with the clock on it, on a scope and a thread of its own, until [close]. */
private class Station(val name: String, port: Int, seeds: Discovery) : AutoCloseable {
    private val done = CountDownLatch(1)
    private val ready = CountDownLatch(1)
    private val opened = AtomicReference<Pair<Cluster, ActorRef<Where>>>()
    private val thread = Thread.ofPlatform().start {
        scope {
            val cluster = cluster(node(name, port), seeds, lively)
            opened.set(cluster to cluster.singleton("clock", whereCodec) { clock(name) })
            ready.countDown()
            done.await()
        }
    }

    val cluster: Cluster
        get() {
            ready.await()
            return opened.get().first
        }

    val clock: ActorRef<Where>
        get() {
            ready.await()
            return opened.get().second
        }

    fun where(within: Duration = 1.minutes) = clock.ask<Where, String>(within) { Where(it) }

    override fun close() {
        done.countDown()
        thread.join()
    }
}

object SingletonSuite : Suite() {
    init {
        test(
            "one clock runs on the oldest member, and moves to the next oldest when it leaves, never running twice",
        ) {
            val ports = List(3) { freePort() }
            val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
            val stations = ports.mapIndexed { i, port -> Station("n${i + 1}", port, seeds) }
            val stop = AtomicBoolean(false)
            try {
                stations.forAll { station ->
                    station.cluster.await(1.minutes) { view ->
                        view.members.count { it.status == Status.Up } == 3
                    } shouldBe true
                }
                withClue("the clock starts before anyone asks it anything") {
                    clockStarted.await(1, TimeUnit.MINUTES) shouldBe true
                }
                val oldest = checkNotNull(Placement.oldest(stations.first().cluster.view.members)).name
                stations.map { it.where() } shouldBe List(3) { oldest.right() }

                val leaving = stations.single { it.name == oldest }
                val staying = stations - leaving
                // An ask already in the clock's mailbox when it stops is a dead letter, as it is when an actor
                // passivates.
                val traffic = Thread.ofVirtual().start {
                    while (!stop.get()) {
                        val _ = staying.first().where(within = 3.seconds)
                    }
                }
                leaving.cluster.leave()
                staying.forAll { station ->
                    station.cluster.await(1.minutes) { view -> view.members.none { it.node.name == oldest } } shouldBe
                        true
                }
                stop.set(true)
                traffic.join()

                val next = checkNotNull(Placement.oldest(staying.first().cluster.view.members)).name
                staying.map { it.where() } shouldBe List(2) { next.right() }
                withClue("the clock ran on two nodes at once") { clocksTwice.get() shouldBe false }
            } finally {
                stop.set(true)
                stations.forEach(Station::close)
            }
        }
    }
}
