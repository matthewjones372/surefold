package dev.surefold.stream

import dev.surefold.Resource
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withClue
import dev.surefold.test.eventually
import org.apache.pekko.actor.ActorSystem
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A hub on every backend: what reaches whom, what is refused, and when a subscription ends. */
object HubSuite : SharedSuite<ActorSystem>() {
    override val shared: Resource<ActorSystem> = actorSystem("surefold-stream-hub-test")

    private const val SETTLE_SECONDS = 10L

    private val backends: List<Backend> = listOf(OnPekko, OnForks, OnTestStreams, OnActors)

    /** Registers [name] once for each backend, named for it, with that backend made fresh for the test. */
    private fun onEvery(name: String, check: (StreamBackend) -> Unit) =
        backends.forEach { backend -> test("$name [${backend.name}]") { system -> check(backend.on(system)) } }

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)

    /** Waits until [n] runs have subscribed: a run registers when it starts, not when it is described. */
    private fun Hub<*>.awaitSubscribers(n: Int) =
        eventually(SETTLE_SECONDS.seconds, every = 1.milliseconds) {
            check(subscribers == n) { "$subscribers subscribed, waiting for $n" }
        }

    init {
        onEvery("what is published before anyone subscribes goes to the first subscriber, in order") { backend ->
            val hub = Hub<Int>()
            hub.publish(1)
            hub.publish(2)

            val first = hub.subscribe().take(3).runCollect().start(backend)
            hub.awaitSubscribers(1)
            hub.publish(3)

            first.exit.settled() shouldBe Exit.Done(listOf(1, 2, 3))
        }

        onEvery("every subscriber gets every element published while it runs") { backend ->
            val hub = Hub<Int>()
            val one = hub.subscribe().runCollect().start(backend)
            val two = hub.subscribe().runCollect().start(backend)
            hub.awaitSubscribers(2)

            (1..5).forAll { hub.publish(it) shouldBe it.right() }
            hub.close()

            one.exit.settled() shouldBe Exit.Done(listOf(1, 2, 3, 4, 5))
            two.exit.settled() shouldBe Exit.Done(listOf(1, 2, 3, 4, 5))
        }

        onEvery("a full subscriber turns an element away for every subscriber") { backend ->
            val hub = Hub<Int>(capacity = 2)
            val stalled = Gate()
            val slow = hub.subscribe().map { stalled.await(); it }.runCollect().start(backend)
            val quick = hub.subscribe().runCollect().start(backend)
            hub.awaitSubscribers(2)

            // The slow reader holds one in its map, and its queue fills behind it: two, and on Pekko the
            // input buffer it reads ahead into as well. Fifty is more than any of them holds.
            val answers = (1..50).map { hub.publish(it).also { Thread.sleep(5) } }
            stalled.open()
            hub.close()

            val refused = answers.count { it == HubRefused.Full.left() }
            withClue("answers: $answers") { (refused > 0) shouldBe true }
            val accepted = answers.mapNotNull { it.getOrNull() }
            withClue("a refused element went to nobody, and an accepted one to both") {
                slow.exit.settled() shouldBe Exit.Done(accepted)
                quick.exit.settled() shouldBe Exit.Done(accepted)
            }
        }

        onEvery("a subscriber that has stopped no longer holds the hub back") { backend ->
            val hub = Hub<Int>(capacity = 1)
            val gone = hub.subscribe().runCollect().start(backend)
            hub.awaitSubscribers(1)

            gone.stop()
            gone.exit.settled()
            hub.awaitSubscribers(0)

            withClue("nobody reads the stopped subscriber's queue, so it would be full after one") {
                (1..3).map { hub.publish(it) } shouldBe listOf(1.right(), 2.right(), 3.right())
            }
        }

        onEvery("closing the hub ends every subscription Done, after what it was sent") { backend ->
            val hub = Hub<String>()
            val reading = hub.subscribe().runCollect().start(backend)
            hub.awaitSubscribers(1)
            hub.publish("a")

            hub.close()

            reading.exit.settled() shouldBe Exit.Done(listOf("a"))
            hub.publish("b") shouldBe HubRefused.Closed.left()
            hub.subscribe().runCollect().run(backend).settled() shouldBe Exit.Done(emptyList())
        }

        onEvery("a stream published to a hub fails with the refusal") { backend ->
            val hub = Hub<Int>(capacity = 2)

            Stream.of(1, 2, 3).publishTo(hub).runCollect().run(backend).settled() shouldBe Exit.Failed(HubRefused.Full)
        }

        test("a hub holds at least one element for each subscriber") { _ ->
            shouldThrow<IllegalArgumentException> { Hub<Int>(capacity = 0) }
        }
    }

    /** Holds every caller of [await] until [open]. */
    private class Gate {
        private val latch = CountDownLatch(1)

        fun await() {
            latch.await(SETTLE_SECONDS, TimeUnit.SECONDS)
        }

        fun open() = latch.countDown()
    }
}
