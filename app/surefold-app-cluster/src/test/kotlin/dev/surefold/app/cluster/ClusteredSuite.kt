package dev.surefold.app.cluster

import dev.surefold.actor.remote.Node
import dev.surefold.app.StartupError
import dev.surefold.app.actor.actors
import dev.surefold.app.single
import dev.surefold.app.testApp
import dev.surefold.app.typesafe.configOf
import dev.surefold.app.use
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.Discovery
import dev.surefold.cluster.Downing
import dev.surefold.cluster.Gossiping
import dev.surefold.cluster.Joining
import dev.surefold.cluster.Status
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldNotBe
import dev.surefold.junit.shouldNotBeNull
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

private val quick =
    Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 1_000.milliseconds)

private fun hocon(name: String, port: Int, seeds: List<Int>, join: String = "static") = """
    surefold.cluster {
      node { name = $name, port = $port }
      join = $join
      static.seeds = [${seeds.joinToString { "\"127.0.0.1:$it\"" }}]
      gossip { probeEvery = 200ms, ackWithin = 60ms, formAfter = 1s }
      whenDowned = stay
    }
""".trimIndent()

private fun settings(name: String, port: Int, seeds: Discovery, released: () -> Unit) = ClusterSettings(
    name = name,
    host = "127.0.0.1",
    port = port,
    joining = { Joining(seeds, Downing.keepMajority(), released) },
    gossiping = quick,
    whenDowned = WhenDowned.Stay,
)

/** Spec 0096: a cluster as a node, joined as a HOCON section says. */
object ClusteredSuite : Suite() {
    init {
        test("three nodes join from config alone, each ready once all three are up") {
            val ports = List(3) { freePort() }
            val allUp = CountDownLatch(3)
            val done = CountDownLatch(1)
            val names = listOf("a", "b", "c")
            // Each node reads its readiness on a thread of its own, so the reading comes back through a future.
            val ready = names.map { CompletableFuture<Boolean>() }
            val nodes = names.zip(ports).mapIndexed { i, (name, port) ->
                val module = configOf(hocon(name, port, ports)) + actors() + cluster("surefold.cluster")
                Thread.ofPlatform().start {
                    testApp(module) { cluster: Cluster ->
                        ready[i].complete(cluster.ready())
                        while (cluster.view.members.count { it.status == Status.Up } < 3) Thread.sleep(50)
                        allUp.countDown()
                        done.await(1, TimeUnit.MINUTES)
                    }
                }
            }
            allUp.await(1, TimeUnit.MINUTES) shouldBe true
            done.countDown()
            nodes.forEach(Thread::join)
            // A node that never reached its reading never counted down, which fails the check above.
            ready.mapNotNull { it.getNow(null) }.forAll { it shouldBe true }
        }

        test("a backend not on the classpath refuses the start, naming the module to add") {
            val module =
                configOf(hocon("a", freePort(), emptyList(), join = "kubernetes")) + actors() +
                    cluster("surefold.cluster")
            module.use { _: Cluster -> }.leftOrNull().shouldBeInstanceOf<StartupError.Refused> { refused ->
                refused.reason shouldContain "join = kubernetes needs surefold-cluster-kubernetes on the classpath"
            }
        }

        test("a section missing what a node needs refuses the start, naming every fault") {
            val module = configOf("surefold.cluster { join = static }") + actors() + cluster("surefold.cluster")
            module.use { _: Cluster -> }.leftOrNull().shouldBeInstanceOf<StartupError.Refused> { refused ->
                // Typesafe Config's own words, one fault for the name and one for the port, both said at once.
                refused.reason shouldContain "No configuration setting found for key 'node'"
                refused.reason.split("; ").size shouldBe 2
            }
        }

        test("the joining is closed only after the node has left its cluster") {
            val ports = List(2) { freePort() }
            val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
            var joined: Cluster? = null
            var closed = false
            var statusAtClose: Status? = null
            // The joining's release reads the cluster where the joining is closed; the reading comes back through here.
            val joinedAtClose = AtomicReference<Cluster?>()
            val stays = CountDownLatch(1)
            val other = Thread.ofPlatform().start {
                testApp(actors() + cluster(settings("stays", ports[0], seeds) {})) { _: Cluster ->
                    stays.await(1, TimeUnit.MINUTES)
                }
            }
            val leaving = settings("leaves", ports[1], seeds) {
                val cluster = joined
                joinedAtClose.set(cluster)
                if (cluster != null) {
                    statusAtClose = cluster.view.members.firstOrNull { it.node == cluster.self }?.status
                    closed = true
                }
            }
            testApp(actors() + cluster(leaving)) { cluster: Cluster ->
                joined = cluster
                while (cluster.view.members.count { it.status == Status.Up } < 2) Thread.sleep(50)
            }
            stays.countDown()
            other.join()
            joinedAtClose.get().shouldNotBeNull()
            closed shouldBe true
            // Leaving or already out: anything but still Up, which a joining closed before the leave would see.
            statusAtClose shouldNotBe Status.Up
        }

        test("a node restarted at its address is not ended by its earlier life's downing") {
            val ports = List(3) { freePort() }
            val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
            val done = CountDownLatch(1)

            // Each goes as a crashed node does, so the others still hold n3's earlier life when it comes back.
            fun crashing(name: String, port: Int, exit: () -> Unit = {}) = actors() +
                single<ClusterSettings> {
                    ClusterSettings(
                        name,
                        "127.0.0.1",
                        port,
                        { Joining(seeds, Downing.keepMajority()) },
                        quick,
                        0.seconds,
                    )
                } + clustered(exit)
            val others = listOf("n1", "n2").zip(ports).map { (name, port) ->
                Thread.ofPlatform().start {
                    testApp(crashing(name, port)) { _: Cluster -> done.await(2, TimeUnit.MINUTES) }
                }
            }
            testApp(crashing("n3", ports[2])) { cluster: Cluster ->
                while (cluster.view.members.count { it.status == Status.Up } < 3) Thread.sleep(50)
            }

            var exits = 0
            testApp(crashing("n3", ports[2]) { exits++ }) { cluster: Cluster ->
                // Up, and told of its earlier life's downing, which names this address.
                fun earlierDowned() =
                    cluster.view.members.any {
                        it.node == cluster.self && !cluster.isSelf(it) && it.status == Status.Down
                    }
                while (!earlierDowned()) Thread.sleep(50)
                Thread.sleep(500)
            }
            done.countDown()
            others.forEach(Thread::join)
            exits shouldBe 0
        }
    }
}
