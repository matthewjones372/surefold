package dev.surefold.app.actor

import dev.surefold.actor.behaviour
import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.freePort
import dev.surefold.actor.remote.node
import dev.surefold.actor.spawn
import dev.surefold.actor.stay
import dev.surefold.app.single
import dev.surefold.app.testApp
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.Discovery
import dev.surefold.cluster.Gossiping
import dev.surefold.cluster.MemberEvent
import dev.surefold.cluster.Status
import dev.surefold.cluster.cluster
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeLessThan
import dev.surefold.scope
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val quick =
    Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 1_000.milliseconds)

object LeaveOnReleaseSuite : Suite() {
    init {
        test("a cluster made in the actors' scope leaves it as the application is released") {
            val ports = List(2) { freePort() }
            val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
            val heard = ConcurrentLinkedQueue<MemberEvent>()
            val bothUp = CountDownLatch(2)
            val removed = CountDownLatch(1)
            val stays = Thread.ofPlatform().start {
                scope {
                    val cluster = cluster(node("stays", ports[0]), seeds, quick)
                    val listener = spawn(
                        "listener",
                        behaviour<MemberEvent, Unit>(Unit) { _, _, event ->
                            heard += event
                            if (event is MemberEvent.Up) bothUp.countDown()
                            if (event is MemberEvent.Removed && event.member.node.name == "app") removed.countDown()
                            stay()
                        },
                    )
                    cluster.subscribe(listener)
                    removed.await(1, TimeUnit.MINUTES)
                }
            }

            val module =
                actors() + single { actors: Actors -> actors.within { cluster(node("app", ports[1]), seeds, quick) } }
            var released = TimeSource.Monotonic.markNow()
            testApp(module) { _: Cluster ->
                bothUp.await(1, TimeUnit.MINUTES) shouldBe true
                released = TimeSource.Monotonic.markNow()
            }

            // Out before the 20 s a node left alone waits to down itself. How it left is the Leaving status asserted
            // below, which a self-down would not carry; this bound only catches a leave that hangs.
            released.elapsedNow() shouldBeLessThan 20.seconds

            removed.await(1, TimeUnit.MINUTES) shouldBe true
            stays.join()
            val app = heard.filter { it.member.node.name == "app" }
            app.map { it::class.simpleName } shouldBe listOf("Up", "Removed")
            app.last().member.status shouldBe Status.Leaving
        }
    }
}
