package io.github.matthewjones372.surefold.kafka

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withClue
import io.github.matthewjones372.surefold.stream.Exit
import io.github.matthewjones372.surefold.stream.start
import io.github.matthewjones372.surefold.stream.take
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Spec 0158: what a rebalance does to the records a consumer has handed out and not yet handled. */
object RebalanceSuite : SharedSuite<KafkaKit>() {
    override val shared: Resource<KafkaKit> = kafkaKit("surefold-kafka-rebalance-test")

    private const val GENEROUS_SECONDS = 60L
    private const val EACH = 3

    // Short, so a member whose stream is busy rejoins within seconds through its keeper's polls; at 2s, a Pekko member
    // is dropped from the group before its keeper's polls finish the rejoin (see spec 0158).
    private const val POLL_INTERVAL_MILLIS = 10_000

    // Short, so a member hears of a rebalance at once and the second member joins promptly.
    private const val HEARTBEAT_MILLIS = 100

    // Long enough for the first member to be inside its revoke before its records are let go.
    private val HELD = 2.seconds

    private fun strings(kit: KafkaKit, group: String, topic: Topic, rebalance: Rebalance) = Kafka.consume(
        mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kit.broker.bootstrap,
            ConsumerConfig.GROUP_ID_CONFIG to group,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to POLL_INTERVAL_MILLIS,
            ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG to HEARTBEAT_MILLIS,
        ),
        topic,
        key = StringDeserializer(),
        value = StringDeserializer(),
        rebalance = rebalance,
    )

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS)

    /**
     * How many of the first member's records a second member reads again, once it joins the group while the first
     * has all six in hand. The first lets them go once the second has read a record, or after [held].
     */
    private fun readAgain(kit: KafkaKit, name: String, on: String, rebalance: Rebalance, held: Duration): Int {
        val topic = Topic("$name-$on")
        val group = "$name-$on"
        kit.broker.create(topic, 2)
        kit.broker.sendTo(topic, 0, "a1", "a2", "a3")
        kit.broker.sendTo(topic, 1, "b1", "b2", "b3")

        val inHand = CountDownLatch(2 * EACH)
        val release = CountDownLatch(1)
        val first = ConcurrentLinkedQueue<String>()
        val running = strings(kit, group, topic, rebalance)
            .mapParRecord(2 * EACH) { record ->
                inHand.countDown()
                release.await(GENEROUS_SECONDS, TimeUnit.SECONDS)
                record.value().also(first::add)
            }
            .take(2L * EACH)
            .runCommitting()
            .start(kit.backend(on))
        inHand.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true

        val read = CountDownLatch(1)
        val second = ConcurrentLinkedQueue<String>()
        val joined = strings(kit, group, topic, rebalance)
            .mapRecord { record -> record.value().also(second::add).also { read.countDown() } }
            .runCommitting()
            .start(kit.backend(on))
        val _ = read.await(held.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        release.countDown()
        running.exit.settled() shouldBe Exit.Done(2L * EACH)

        // Once the first has gone, the second owns both partitions and reads on to what is sent now.
        kit.broker.sendTo(topic, 0, "a4")
        kit.broker.sendTo(topic, 1, "b4")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GENEROUS_SECONDS)
        while (!second.containsAll(listOf("a4", "b4")) && System.nanoTime() < deadline) Thread.sleep(10)
        joined.stop()
        joined.exit.settled() shouldBe Exit.Done(second.size.toLong())
        return second.count { it in first }
    }

    init {
        backends.forEach { on ->
            test("today, a member joining mid-stream reads again all the first member had in hand [$on]") {
                    kit ->
                // An eager rebalance takes every partition away, the one given back too, so marks made after it on
                // either are dropped.
                withClue("every record it had in hand, on the partition it kept as well as the one that moved") {
                    readAgain(kit, "immediately", on, Rebalance.immediately, GENEROUS_SECONDS.seconds) shouldBe 2 * EACH
                }
            }

            test("a rebalance that waits for what was handed out leaves the new owner nothing to read again [$on]") {
                    kit ->
                readAgain(kit, "waiting", on, Rebalance.waitFor(GENEROUS_SECONDS.seconds), HELD) shouldBe 0
            }
        }

        test("a rebalance cannot wait for no time at all") {
            shouldThrow<IllegalArgumentException>({ Rebalance.waitFor(Duration.ZERO) }) { }
        }
    }
}
