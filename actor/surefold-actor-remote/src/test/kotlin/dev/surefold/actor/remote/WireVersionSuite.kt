package dev.surefold.actor.remote

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

private const val MAGIC = 0x4C41524B

/** What a transport hands its node, kept whole: the carried map is what is under test. */
private class Frames : Listener {
    val received = LinkedBlockingQueue<Frame>()

    override fun received(from: Node, frame: Frame) = received.put(frame)

    fun next(): Frame = checkNotNull(received.poll(1, TimeUnit.MINUTES)) { "no frame arrived" }
}

/**
 * A node built before spec 0122, written out by hand as the wire stood: it speaks version 1 alone, closes on any other
 * offer without answering, and reads frames with no map in them.
 */
private class OldNode : AutoCloseable {
    private val server = ServerSocket(0)
    val node = Node("old", "127.0.0.1", server.localPort)
    val payloads = LinkedBlockingQueue<List<Byte>>()
    val refused = LinkedBlockingQueue<Int>()
    private val accepting = Thread.ofVirtual().start {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: java.net.SocketException) {
                return@start
            }
            Thread.ofVirtual().start { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        socket.use {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val magic = input.readInt()
            check(magic == MAGIC) { "expected $MAGIC but was $magic" }
            val version = input.readInt()
            if (version != 1) {
                refused.put(version)
                return
            }
            input.readUTF()
            input.readLong()
            output.oldHello(node)
            try {
                while (true) payloads.put(input.oldFrame())
            } catch (_: java.io.EOFException) {
                // The new node closed its connection: nothing more to read.
            }
        }
    }

    override fun close() {
        server.close()
        accepting.join()
    }
}

/** A version-1 frame's payload: whatever of its size the path and incarnation leave. */
private fun DataInputStream.oldFrame(): List<Byte> {
    val size = readInt()
    val path = ByteArray(readInt()).also(::readFully)
    readLong()
    return ByteArray(size - Int.SIZE_BYTES - path.size - Long.SIZE_BYTES).also(::readFully).toList()
}

private fun DataOutputStream.oldHello(self: Node) {
    writeInt(MAGIC)
    writeInt(1)
    writeUTF(self.toString())
    writeLong(1)
    flush()
}

/** Spec 0122's wire: version 2 carries the map, and a node still talks to one that speaks only version 1. */
object WireVersionSuite : Suite() {
    init {
        test("between two nodes on version 2, what a frame carried arrives with it") {
            opening {
                val two = Node("two", "127.0.0.1", freePort())
                val heard = Frames()
                Transport(two, heard).closedAfter().listen()
                val one = Transport(Node("one", "127.0.0.1", freePort()), Frames()).closedAfter()

                val carried = mapOf("traceparent" to "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                one.send(two, Frame("/user/clinic", 1, byteArrayOf(7, 8), carried = carried))
                one.send(two, Frame("/user/clinic", 1, byteArrayOf(9)))

                heard.next().let {
                    it.carried shouldBe carried
                    it.payload.toList() shouldBe listOf<Byte>(7, 8)
                }
                heard.next().let {
                    it.carried shouldBe emptyMap()
                    it.payload.toList() shouldBe listOf<Byte>(9)
                }
            }
        }

        test("a node built before reads what a new node sends it, on version 1, without the map") {
            opening {
                val old = OldNode().closedAfter()
                val one =
                    Transport(Node("one", "127.0.0.1", freePort()), Frames(), retryFrom = 10.milliseconds).closedAfter()

                val carried = mapOf("traceparent" to "00-x-y-01")
                one.send(old.node, Frame("/user/clinic", 1, byteArrayOf(1, 2, 3), carried = carried))

                old.refused.poll(1, TimeUnit.MINUTES) shouldBe 2
                old.payloads.poll(1, TimeUnit.MINUTES) shouldBe listOf<Byte>(1, 2, 3)
            }
        }

        test("a node built before is answered on version 1, and what it sends is read") {
            opening {
                val two = Node("two", "127.0.0.1", freePort())
                val heard = Frames()
                Transport(two, heard).closedAfter().listen()

                Socket().use { socket ->
                    socket.connect(InetSocketAddress(two.host, two.port))
                    val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                    output.oldHello(Node("old", "127.0.0.1", 1))
                    input.readInt() shouldBe MAGIC
                    input.readInt() shouldBe 1
                    val path = "/user/clinic".toByteArray()
                    output.writeInt(Int.SIZE_BYTES + path.size + Long.SIZE_BYTES + 2)
                    output.writeInt(path.size)
                    output.write(path)
                    output.writeLong(1)
                    output.write(byteArrayOf(4, 5))
                    output.flush()

                    heard.next().let {
                        it.payload.toList() shouldBe listOf<Byte>(4, 5)
                        it.carried shouldBe emptyMap()
                    }
                }
            }
        }
    }
}
