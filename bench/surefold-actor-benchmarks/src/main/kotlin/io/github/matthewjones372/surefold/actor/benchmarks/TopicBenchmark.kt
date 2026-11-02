package io.github.matthewjones372.surefold.actor.benchmarks

import dev.surefold.actor.Behaviour
import dev.surefold.actor.Topic
import dev.surefold.actor.behaviour
import dev.surefold.actor.spawn
import dev.surefold.actor.stay
import dev.surefold.cluster.topic
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.Props
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.apache.pekko.actor.typed.pubsub.Topic as PekkoTopic

/** Subscribers on each node, as spec 0092 asks: 3 nodes × 10. */
private const val SUBSCRIBERS_EACH = 10

private const val SUBSCRIBERS = NODES * SUBSCRIBERS_EACH

/** Publishes per invocation: under every mailbox of 1,024 a surefold topic and its subscribers have. */
private const val PUBLISHES = 100

/** How long to wait for the probe before publishing it again, while the nodes find each other's topics. */
private const val PROBE_EVERY_MILLIS = 100L

/** The count down each subscriber makes, one per price heard: every node is in this JVM. */
private val heard = AtomicReference(CountDownLatch(0))

/** The subscribers that have heard a probe, and the latch that opens once all of them have. */
private class Hearing {
    private val subscribers = ConcurrentHashMap.newKeySet<String>()
    val all = CountDownLatch(1)

    fun probed(subscriber: String) {
        if (subscribers.add(subscriber) && subscribers.size == SUBSCRIBERS) all.countDown()
    }
}

private fun hear(hearing: Hearing, subscriber: String, n: Int) =
    if (n < 0) hearing.probed(subscriber) else heard.get().countDown()

/** Publishes a probe until every subscriber on every node has heard one, so no trial measures a topic still forming. */
private fun awaitSubscribers(hearing: Hearing, publish: (Int) -> Unit) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ASK_WITHIN.toSeconds() * NODES)
    do {
        publish(-1)
        if (hearing.all.await(PROBE_EVERY_MILLIS, TimeUnit.MILLISECONDS)) return
    } while (System.nanoTime() < deadline)
    error("not every subscriber heard the topic")
}

private fun surefoldSubscriber(hearing: Hearing, name: String): Behaviour<Int, Unit, Nothing> =
    behaviour(Unit) { _, _, n ->
        hear(hearing, name, n)
        stay()
    }

private fun pekkoSubscriber(hearing: Hearing, name: String): Behavior<PekkoPrice> = Behaviors.receiveMessage { price ->
    hear(hearing, name, price.n)
    Behaviors.same()
}

/** Three surefold nodes with the topic on each and ten subscribers to it on each, published to from the first. */
@State(Scope.Benchmark)
open class SurefoldTopic {
    internal lateinit var prices: Topic<Int>
    private lateinit var nodes: SurefoldTrio<Topic<Int>>

    @Setup(Level.Trial)
    fun start() {
        val hearing = Hearing()
        nodes = SurefoldTrio { cluster, node ->
            cluster.topic("prices", priceCodec).also { topic ->
                repeat(SUBSCRIBERS_EACH) { i ->
                    topic.subscribe(
                        spawn("subscriber-$i", surefoldSubscriber(hearing, "$node-$i"), capacity = CAPACITY),
                    )
                }
            }
        }
        prices = nodes.parts.first()
        awaitSubscribers(hearing, prices::publish)
    }

    @TearDown(Level.Trial)
    fun stop() = nodes.close()
}

/** Three Pekko nodes with a `Topic` on each and ten subscribers to it on each, published to from the first. */
@State(Scope.Benchmark)
open class PekkoTopicNodes {
    lateinit var prices: ActorRef<PekkoTopic.Command<PekkoPrice>>
    private lateinit var nodes: PekkoTrio

    @Setup(Level.Trial)
    fun start() {
        val hearing = Hearing()
        nodes = PekkoTrio()
        val topics = nodes.systems.mapIndexed { n, system ->
            val topic =
                system.systemActorOf(PekkoTopic.create(PekkoPrice::class.java, "prices"), "prices", Props.empty())
            repeat(SUBSCRIBERS_EACH) { i ->
                val subscriber = pekkoSubscriber(hearing, "${nodeName(n)}-$i")
                topic.tell(PekkoTopic.subscribe(system.systemActorOf(subscriber, "subscriber-$i", Props.empty())))
            }
            topic
        }
        prices = topics.first()
        awaitSubscribers(hearing) { prices.tell(PekkoTopic.publish(PekkoPrice(it))) }
    }

    @TearDown(Level.Trial)
    fun stop() = nodes.close()
}

/**
 * [PUBLISHES] publishes on the first node, until each has reached all 30 subscribers, 10 on each of 3 nodes. The row
 * is one publish. surefold: `cluster.topic(...).publish`, which tells the topic on each other `Up` member once. Pekko:
 * `Topic.publish` to its distributed pub-sub `Topic`, which tells each topic instance the receptionist has found
 * once.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@OperationsPerInvocation(PUBLISHES)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class TopicPublishBenchmark {

    @Benchmark
    fun surefold(topic: SurefoldTopic) = publishes { n -> topic.prices.publish(n) }

    @Benchmark
    fun pekko(topic: PekkoTopicNodes) = publishes { n -> topic.prices.tell(PekkoTopic.publish(PekkoPrice(n))) }

    private fun publishes(publish: (Int) -> Unit) {
        val done = CountDownLatch(PUBLISHES * SUBSCRIBERS)
        heard.set(done)
        repeat(PUBLISHES, publish)
        check(done.await(ASK_WITHIN.toMillis(), TimeUnit.MILLISECONDS)) { "not every subscriber heard every price" }
    }
}
