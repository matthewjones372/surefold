package dev.surefold.app.pekko

import dev.surefold.Resource
import dev.surefold.app.probe
import dev.surefold.app.single
import dev.surefold.app.testApp
import dev.surefold.app.use
import dev.surefold.app.validate
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldHaveSize
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.resource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PostStop
import org.apache.pekko.actor.typed.javadsl.Behaviors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

data class Add(val by: Int)

data class Greet(val who: String)

private fun counting(total: AtomicInteger, counted: CountDownLatch): Behavior<Add> =
    Behaviors.receive(Add::class.java)
        .onMessage(Add::class.java) { total.addAndGet(it.by); counted.countDown(); Behaviors.same() }
        .build()

private fun greeting(stopped: CountDownLatch): Behavior<Greet> =
    Behaviors.receive(Greet::class.java)
        .onMessage(Greet::class.java) { Behaviors.same<Greet>() }
        .onSignal(PostStop::class.java) { stopped.countDown(); Behaviors.same<Greet>() }
        .build()

object ActorSuite : SharedSuite<ActorSystem>() {
    override val shared: Resource<ActorSystem> = resource {
        install({ ActorSystem.create("surefold-app-pekko-test") }) { system, _ -> system.terminate() }
    }

    init {
        test("an actor is a node, and what depends on it is handed its ref") { system ->
            val total = AtomicInteger()
            val counted = CountDownLatch(2)
            val module = single<ActorSystem> { system } + actor<Add>("counter") { counting(total, counted) }

            testApp(module) { counter: ActorRef<Add> ->
                counter.tell(Add(2))
                counter.tell(Add(3))
                counted.await(5, TimeUnit.SECONDS) shouldBe true
            }

            total.get() shouldBe 5
        }

        test("two protocols are two nodes") { system ->
            val module = single<ActorSystem> { system } +
                actor<Add>("pair-counter") { counting(AtomicInteger(), CountDownLatch(1)) } +
                actor<Greet>("pair-greeter") { greeting(CountDownLatch(1)) }

            module.validate().getOrNull().shouldNotBeNull { plan -> plan.layers.flatten() shouldHaveSize 3 }
        }

        test("an actor is stopped when the graph is given back") { system ->
            val stopped = CountDownLatch(1)
            val module = single<ActorSystem> { system } + actor<Greet>("stopping") { greeting(stopped) }

            module.use { _: ActorRef<Greet> -> }.getOrNull().shouldNotBeNull()
            stopped.count shouldBe 0L
        }

        test("an actor that never answers its probe fails the start") { system ->
            val module = single<ActorSystem> { system } +
                actor<Greet>("silent") { greeting(CountDownLatch(1)) }
                    .probe("silent", timeout = 1.seconds) { _: ActorRef<Greet> -> false }

            module.use { _: ActorRef<Greet> -> }.leftOrNull().shouldNotBeNull()
        }
    }
}
