package dev.surefold.actor.remote

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeLessThan
import dev.surefold.junit.shouldContainExactly
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A port nothing is listening on yet: bound and let go, so a node can be named before it listens. */

private fun node(name: String) = Node(name, "127.0.0.1", freePort())

private fun frame(n: Int) = Frame("/user/clinic", 1, byteArrayOf(n.toByte(), (n shr 8).toByte()))

private val Frame.n: Int get() = (payload[0].toInt() and 0xff) or ((payload[1].toInt() and 0xff) shl 8)

/** Everything a transport tells its node, as events a test can wait for one at a time. */
private class Heard : Listener {
    val received = LinkedBlockingQueue<Pair<Node, Int>>()
    val connected = LinkedBlockingQueue<Node>()
    val disconnected = LinkedBlockingQueue<Node>()
    val dropped = ConcurrentLinkedQueue<Int>()
    var droppedCount = CountDownLatch(0)

    override fun received(from: Node, frame: Frame) = received.put(from to frame.n)

    override fun connected(peer: Node, lane: Lane) = connected.put(peer)

    override fun disconnected(peer: Node, lane: Lane) = disconnected.put(peer)

    override fun dropped(peer: Node, frame: Frame) {
        dropped += frame.n
        droppedCount.countDown()
    }

    fun take(count: Int): List<Pair<Node, Int>> = List(count) { checkNotNull(received.poll(1, TimeUnit.MINUTES)) }
}

object WireSuite : Suite() {
    init {
        test("frames cross between two nodes in the order each sent them, both ways") {
            opening {
                val one = node("one")
                val two = node("two")
                val heardByOne = Heard()
                val heardByTwo = Heard()
                val first = Transport(one, heardByOne).closedAfter().apply { listen() }
                val second = Transport(two, heardByTwo).closedAfter().apply { listen() }

                (1..5_000).forEach { first.send(two, frame(it)) }
                (1..5_000).forEach { second.send(one, frame(it)) }

                heardByTwo.take(5_000) shouldBe (1..5_000).map { one to it }
                heardByOne.take(5_000) shouldBe (1..5_000).map { two to it }
            }
        }

        test("a peer that goes and comes back is connected to again, and what is sent after arrives in order") {
            opening {
                val one = node("one")
                val two = node("two")
                val heardByOne = Heard()
                val first = Transport(one, heardByOne, retryFrom = 10.milliseconds).closedAfter()
                val before = Heard()
                val second = Transport(two, before).apply { listen() }
                first.send(two, frame(1))
                before.take(1) shouldContainExactly listOf(one to 1)

                second.close()
                heardByOne.disconnected.poll(1, TimeUnit.MINUTES) shouldBe two
                // The first connection's event, drained before the peer is back, so the next one is the reconnection's.
                heardByOne.connected.clear()
                val after = Heard()
                Transport(two, after).closedAfter().listen()
                heardByOne.connected.poll(1, TimeUnit.MINUTES) shouldBe two
                (2..1_000).forEach { first.send(two, frame(it)) }

                after.take(999) shouldBe (2..1_000).map { one to it }
            }
        }

        test("what is sent to a peer nobody answers for is dropped, and said to be") {
            opening {
                val heard = Heard().apply { droppedCount = CountDownLatch(3) }
                val first = Transport(node("one"), heard, retryFrom = 1.milliseconds).closedAfter()

                val nobody = node("nobody")
                (1..3).forEach { first.send(nobody, frame(it)) }

                heard.droppedCount.await(1, TimeUnit.MINUTES) shouldBe true
                heard.dropped.toList() shouldContainExactly listOf(1, 2, 3)
            }
        }

        test("while a peer has not yet answered, frames wait up to the room, and the rest are dropped at once") {
            opening {
                // Accepts and says nothing, so the handshake never finishes and every frame sent waits for it.
                val silent = ServerSocket(0).closedAfter()
                val accepted = Thread.ofVirtual().start { silent.accept() }
                val heard = Heard()
                val first = Transport(node("one"), heard, room = 4).closedAfter()

                (1..10).forEach { first.send(Node("silent", "127.0.0.1", silent.localPort), frame(it)) }

                heard.dropped.toList() shouldContainExactly (5..10).toList()
                accepted.join()
            }
        }

        test("a peer known only by host and port is whichever node answers there") {
            opening {
                val two = node("two")
                val heardByTwo = Heard()
                Transport(two, heardByTwo).closedAfter().listen()
                val first = Transport(node("one"), Heard()).closedAfter()

                first.send(Node("", two.host, two.port), frame(7))

                heardByTwo.take(1).map { it.second } shouldContainExactly listOf(7)
            }
        }

        test("a peer that answers under another name than the one sent to is not connected to") {
            opening {
                val two = node("two")
                Transport(two, Heard()).closedAfter().listen()
                val heard = Heard().apply { droppedCount = CountDownLatch(1) }
                val first = Transport(node("one"), heard, retryFrom = 1.milliseconds).closedAfter()

                first.send(Node("three", two.host, two.port), frame(1))

                heard.droppedCount.await(1, TimeUnit.MINUTES) shouldBe true
                heard.connected.isEmpty() shouldBe true
            }
        }

        test("what was sent to a connected peer just before close still reaches it") {
            opening {
                val one = node("one")
                val two = node("two")
                val heardByTwo = Heard()
                Transport(two, heardByTwo).closedAfter().listen()
                val heardByOne = Heard()
                // The claim is that close drains what was queued, not how fast: 8,000 frames on a loaded runner can
                // take longer than the default second, and every frame left after it is dropped.
                val first = Transport(one, heardByOne, drainWithin = 30.seconds)
                first.send(two, frame(0))
                heardByTwo.take(1) shouldContainExactly listOf(one to 0)

                (1..8_000).forEach { first.send(two, frame(it)) }
                first.close()

                heardByTwo.take(8_000) shouldBe (1..8_000).map { one to it }
                heardByOne.dropped.isEmpty() shouldBe true
            }
        }

        test("a peer that stops reading holds a close no longer than its drain") {
            opening {
                // Answers the handshake as "stuck", then reads nothing, so the sender's socket fills and its writes
                // block.
                val stuck = ServerSocket(0).closedAfter()
                val answered = Thread.ofVirtual().start {
                    val socket = stuck.accept()
                    val input = DataInputStream(socket.getInputStream())
                    input.readInt()
                    input.readInt()
                    input.readUTF()
                    input.readLong()
                    DataOutputStream(socket.getOutputStream()).apply {
                        writeInt(0x4C41524B)
                        writeInt(2)
                        writeUTF("stuck@127.0.0.1:${stuck.localPort}")
                        writeLong(1)
                        flush()
                    }
                }
                val heard = Heard()
                val first = Transport(node("one"), heard, drainWithin = 200.milliseconds)
                val peer = Node("stuck", "127.0.0.1", stuck.localPort)
                first.send(peer, frame(0))
                heard.connected.poll(1, TimeUnit.MINUTES) shouldBe peer
                val big = Frame("/user/clinic", 1, ByteArray(64 * 1024))
                repeat(1_000) { first.send(peer, big) }

                val closing = TimeSource.Monotonic.markNow()
                first.close()

                closing.elapsedNow() shouldBeLessThan 5.seconds
                heard.dropped.isEmpty() shouldBe false
                answered.join()
            }
        }

        test("a node names itself as name at host and port, and reads back the same") {
            Node.parse("shop-1@10.0.0.7:25520") shouldBe Node("shop-1", "10.0.0.7", 25520)
            Node("shop-1", "10.0.0.7", 25520).toString() shouldBe "shop-1@10.0.0.7:25520"
        }
    }
}
