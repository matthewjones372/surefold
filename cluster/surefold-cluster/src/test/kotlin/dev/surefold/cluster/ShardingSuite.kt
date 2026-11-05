package dev.surefold.cluster

import dev.surefold.actor.Reply
import dev.surefold.actor.Signal
import dev.surefold.actor.ask
import dev.surefold.actor.become
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
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.withClue
import dev.surefold.scope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private sealed interface Tab

private data class Spend(val pence: Int) : Tab

/** Answers with the node the tab runs on and what has been spent on it there. */
private data class Bill(val reply: Reply<String>) : Tab

private val tabCodec =
    object : MessageCodec<Tab> {
        override fun write(message: Tab, out: WireOut) =
            when (message) {
                is Spend -> {
                    out.int(1)
                    out.int(message.pence)
                }

                is Bill -> {
                    out.int(2)
                    out.reply(message.reply, Codecs.string)
                }
            }

        override fun read(input: WireIn): Tab =
            when (val tag = input.int()) {
                1 -> Spend(input.int())
                2 -> Bill(input.reply(Codecs.string))
                else -> error("no tab message has the tag $tag")
            }
    }

/**
 * How many of each tab run now, across every node of one test's cluster; and whether any ever ran twice at once. Each
 * test has its own, since tests running at once use the same tab ids on clusters of their own.
 */
private class TabsRunning {
    val tabs = ConcurrentHashMap<String, AtomicInteger>()
    val twice = AtomicBoolean(false)
}

private fun tab(on: String, id: String, running: TabsRunning) =
    behaviour<Tab, Int>(0) { _, spent, message ->
            when (message) {
                is Spend -> become(spent + message.pence)
                is Bill -> stay().also { message.reply("$on:$spent") }
            }
        }
        .onStart {
            if (running.tabs.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet() > 1) running.twice.set(true)
        }
        .onSignal { _, _, signal ->
            if (signal == Signal.Stopping) running.tabs.getValue(id).decrementAndGet()
            stay()
        }

private val brisk =
    Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 1_000.milliseconds)

/** A node with the tabs sharded on it, on a scope and a thread of its own, until [close]. */
private class Bar(val name: String, port: Int, seeds: Discovery, running: TabsRunning) : AutoCloseable {
    private val done = CountDownLatch(1)
    private val ready = CountDownLatch(1)
    private val opened = AtomicReference<Pair<Cluster, Sharded<Tab>>>()
    private val thread =
        Thread.ofPlatform().start {
            scope {
                val cluster = cluster(node(name, port), seeds, brisk)
                val tabs = cluster.sharding("tab", tabCodec, passivateAfter = 1.minutes) { tab(name, it, running) }
                opened.set(cluster to tabs)
                ready.countDown()
                done.await()
            }
        }

    val cluster: Cluster
        get() {
            ready.await()
            return opened.get().first
        }

    val tabs: Sharded<Tab>
        get() {
            ready.await()
            return opened.get().second
        }

    override fun close() {
        done.countDown()
        thread.join()
    }
}

private fun List<Bar>.awaitUp(count: Int) = forAll { bar ->
    withClue("${bar.name} sees $count Up") {
        bar.cluster.await(1.minutes) { view -> view.members.count { it.status == Status.Up } == count } shouldBe true
    }
}

object ShardingSuite : Suite() {
    init {
        test("an entity told and asked from any node runs on the one node that owns it") {
            val ports = List(3) { freePort() }
            val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
            val running = TabsRunning()
            val bars = ports.mapIndexed { i, port -> Bar("n${i + 1}", port, seeds, running) }
            try {
                bars.forAll { bar ->
                    bar.cluster.await(1.minutes) { view -> view.members.count { it.status == Status.Up } == 3 } shouldBe
                        true
                }
                val shard = Placement.shardOf("t-42", Sharding.SHARDS)
                val owner = Placement.owner("tab", shard, bars.first().cluster.view.members)?.name

                // Each node tells, then asks on the same way through: its bill counts its own spend and every one
                // before.
                val bills = bars.map { bar ->
                    val tab = bar.tabs.entity("t-42")
                    tab.tell(Spend(10))
                    tab.ask<Tab, String>(1.minutes) { Bill(it) }
                }

                bills shouldBe listOf("$owner:10".right(), "$owner:20".right(), "$owner:30".right())
            } finally {
                bars.forEach(Bar::close)
            }
        }

        test("a node joining and one leaving while tabs are told throughout never runs one tab on two nodes") {
            val ports = List(4) { freePort() }
            val seeds = Discovery.static(*ports.take(3).map { Node("", "127.0.0.1", it) }.toTypedArray())
            val running = TabsRunning()
            val bars = ports.take(3).mapIndexed { i, port -> Bar("n${i + 1}", port, seeds, running) }.toMutableList()
            val stop = AtomicBoolean(false)
            try {
                bars.awaitUp(3)
                val tabs = bars.first().tabs
                val ids = (1..200).map { "t-$it" }
                // Tells and asks every tab from the first node for as long as the membership is changing. An ask
                // already in a tab's mailbox when its shard moves is a dead letter, as it is when a tab passivates.
                val traffic =
                    Thread.ofVirtual().start {
                        while (!stop.get()) {
                            ids.forEach { id ->
                                tabs.entity(id).tell(Spend(1))
                                val _ = tabs.entity(id).ask<Tab, String>(3.seconds) { Bill(it) }
                            }
                        }
                    }

                bars += Bar("n4", ports[3], seeds, running)
                bars.awaitUp(4)
                bars[1].cluster.leave()
                bars
                    .filter { it.name != "n2" }
                    .forAll { bar ->
                        bar.cluster.await(1.minutes) { view -> view.members.none { it.node.name == "n2" } } shouldBe
                            true
                    }
                stop.set(true)
                traffic.join()

                withClue("a tab ran on two nodes at once") { running.twice.get() shouldBe false }
                val unanswered = ids.filter { id -> tabs.entity(id).ask<Tab, String>(1.minutes) { Bill(it) }.isLeft() }
                withClue("every tab answers once the membership has settled") { unanswered.shouldBeEmpty() }
            } finally {
                stop.set(true)
                bars.forEach(Bar::close)
            }
        }
    }
}
