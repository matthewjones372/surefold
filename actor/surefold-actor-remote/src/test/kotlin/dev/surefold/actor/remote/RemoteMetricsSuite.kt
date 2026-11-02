package dev.surefold.actor.remote

import dev.surefold.Counter
import dev.surefold.Gauge
import dev.surefold.Histogram
import dev.surefold.Metrics
import dev.surefold.actor.Address
import dev.surefold.actor.DeadLetter
import dev.surefold.actor.behaviour
import dev.surefold.actor.onDeadLetter
import dev.surefold.actor.spawn
import dev.surefold.actor.stay
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.metrics
import dev.surefold.scope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.DoubleAdder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Metrics kept by name and tags, which `CapturedMetrics` does not tell apart, with a wait on a gauge's value. */
private class TaggedMetrics : Metrics {
    private val counters = ConcurrentHashMap<Pair<String, Map<String, String>>, DoubleAdder>()
    private val gauges = HashMap<Pair<String, Map<String, String>>, Double>()
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    override fun counter(name: String, tags: Map<String, String>): Counter {
        val adder = counters.computeIfAbsent(name to tags) { DoubleAdder() }
        return Counter { adder.add(it) }
    }

    override fun gauge(name: String, tags: Map<String, String>): Gauge = Gauge { value ->
        lock.withLock {
            gauges[name to tags] = value
            changed.signalAll()
        }
    }

    override fun histogram(name: String, tags: Map<String, String>): Histogram = Histogram { }

    fun counter(name: String, vararg tags: Pair<String, String>): Double = counters[name to tags.toMap()]?.sum() ?: 0.0

    /** Whether the gauge came to read [value] within a minute. */
    fun awaitGauge(name: String, value: Double, vararg tags: Pair<String, String>): Boolean = lock.withLock {
        var left = TimeUnit.MINUTES.toNanos(1)
        while (gauges[name to tags.toMap()] != value) {
            if (left <= 0) return false
            left = changed.awaitNanos(left)
        }
        true
    }
}

object RemoteMetricsSuite : Suite() {
    init {
        test(
            "frames are counted each way per peer, and a peer that goes reads 0 and counts what was dropped for it",
        ) {
            val herePort = freePort()
            val therePort = freePort()
            val here = TaggedMetrics()
            val there = TaggedMetrics()
            val heard = CountDownLatch(10)
            val listening = CountDownLatch(1)
            val done = CountDownLatch(1)
            val other = Thread.ofPlatform().start {
                metrics.locally(there) {
                    scope {
                        val node = node("there", therePort)
                        node.expose(
                            spawn(
                                "echo",
                                behaviour<String, Unit>(Unit) { _, _, _ ->
                                    stay().also {
                                        heard.countDown()
                                    }
                                },
                            ),
                            Codecs.string,
                        )
                        listening.countDown()
                        done.await()
                    }
                }
            }
            listening.await()
            val dropped = CountDownLatch(3)

            metrics.locally(here) {
                scope {
                    onDeadLetter { letter -> if (letter.why == DeadLetter.Why.Unreachable) dropped.countDown() }
                    val node = node("here", herePort)
                    val peer = Node("there", "127.0.0.1", therePort)
                    val echo = node.remote(Address(peer.toString(), "/user/echo", 0), Codecs.string)
                    repeat(10) { echo.tell("frame $it") }
                    heard.await(1, TimeUnit.MINUTES) shouldBe true
                    here.awaitGauge(
                        "surefold.remote.connected",
                        1.0,
                        "node" to "here",
                        "peer" to "$peer",
                    ) shouldBe true

                    done.countDown()
                    other.join()
                    here.awaitGauge(
                        "surefold.remote.connected",
                        0.0,
                        "node" to "here",
                        "peer" to "$peer",
                    ) shouldBe true
                    repeat(3) { echo.tell("lost $it") }
                    dropped.await(1, TimeUnit.MINUTES) shouldBe true

                    val out = arrayOf("node" to "here", "peer" to "$peer", "lane" to "data")
                    here.counter("surefold.remote.frames", *out, "direction" to "out") shouldBe 13.0
                    here.counter("surefold.remote.dropped", *out) shouldBe 3.0
                }
            }
            val from = Node("here", "127.0.0.1", herePort)
            val into = arrayOf("node" to "there", "peer" to "$from", "direction" to "in", "lane" to "data")
            there.counter("surefold.remote.frames", *into) shouldBe 10.0
        }
    }
}
