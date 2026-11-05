package dev.surefold.actor.remote

import dev.surefold.Clock
import dev.surefold.clock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A node as an address names it: `name@host:port`. A node whose name is empty is one only its host and port are known
 * for, as discovery finds a seed: it is whichever node answers there.
 */
data class Node(val name: String, val host: String, val port: Int) {
    override fun toString() = "$name@$host:$port"

    companion object {
        private val form = Regex("""([^@]*)@(.+):(\d+)""")

        fun parse(node: String): Node {
            val parts = requireNotNull(form.matchEntire(node)) { "$node is not name@host:port" }.destructured
            val (name, host, port) = parts
            return Node(name, host, port.toInt())
        }

        private val seed = Regex("""(.+):(\d{1,5})""")

        /** A node known by `host:port` alone, as a seed is written in config: whichever node answers there. */
        fun at(address: String): Node {
            val found = requireNotNull(seed.matchEntire(address.trim())) { "$address is not host:port" }
            val (host, port) = found.destructured
            require(port.toInt() in 1..MAX_PORT) { "$address has no port a node can listen on" }
            return Node("", host, port.toInt())
        }

        private const val MAX_PORT = 65_535
    }
}

/**
 * One message on the wire: the actor it is for, by path and incarnation, and its codec's bytes. On the sending side it
 * keeps the [message] it was written from, so that a frame that is dropped can be a dead letter with it in; that is not
 * written. What [carried] holds rides to a peer that speaks wire version 2 (spec 0122), and is left off for one that
 * speaks only 1.
 */
class Frame(
    val path: String,
    val incarnation: Long,
    val payload: ByteArray,
    val message: Any? = null,
    val carried: Map<String, String> = emptyMap(),
    // The paths of the replies written into [payload] for asks on this node, so a frame dropped unsent can end them.
    val replies: List<String> = emptyList(),
)

/** What a [Transport] tells the node it carries for. Each is called on one of the transport's own threads. */

/**
 * Which of a peer's two connections a frame crosses (spec 0104). The cluster's own traffic, membership, watches and
 * their ends, goes on [Control], a short queue and a connection of its own, so it never waits behind, or is dropped
 * with, the [Data] everything else sends.
 */
enum class Lane {
    Control,
    Data,
}

interface Listener {
    fun received(from: Node, frame: Frame)

    /** [peer] answered the handshake on [lane]'s connection, and what is sent to it there now crosses. */
    fun connected(peer: Node, lane: Lane) = Unit

    /** [lane]'s connection to [peer] went down: what was queued on it has been [dropped], and it is being retried. */
    fun disconnected(peer: Node, lane: Lane) = Unit

    /** [frame] will not reach [peer]: its connection is down, or its queue was full. At most once, as promised. */
    fun dropped(peer: Node, frame: Frame) = Unit
}

private const val MAGIC = 0x4C41524B // "SUREFOLD"

/** The newest wire version this node speaks: 2 carries [Frame.carried]. It reads and writes 1 for an older peer. */
private const val VERSION = 2
private const val FIRST_VERSION = 1

private const val NANOS_PER_MILLI = 1_000_000L

/** A frame larger than this is a stream out of step, not a message: the connection is dropped. */
private const val MAX_FRAME = 16 shl 20

/**
 * Frames between this node and others: a connection to each peer per [Lane] it sends on, opened on first use, on JDK
 * sockets and virtual threads. Each direction has its own connections, so order holds per sender, receiver and lane.
 * What is sent to a peer waits, up to [room] frames on the data lane and [controlRoom] on the control lane (spec 0104),
 * while its connection opens; past that, and whenever an attempt to connect fails or a connection drops, frames are
 * dropped and reported. A dropped connection is retried with a backoff from [retryFrom] doubling to [retryUpTo], on the
 * clock the transport was made on. With [tls], every connection both ways is TLS, and a peer that cannot complete its
 * handshake is one that failed to connect.
 *
 * Closing stops taking frames, then gives each lane whose connection is up [drainWithin], in all, to write what it
 * already holds, so a frame told just before close is not lost to it (spec 0166). What is left after that, and all that
 * waits on a lane not connected, is dropped and reported.
 *
 * A peer is reached by name, and the JDK remembers a name that did not resolve for 10 seconds. A peer named before its
 * host exists, as a seed or a pod is, stays out of reach that long after it appears, unless the service sets
 * `networkaddress.cache.negative.ttl` to 0 before its first lookup (spec 0104).
 */
class Transport(
    val self: Node,
    private val listener: Listener,
    private val room: Int = 8192,
    private val controlRoom: Int = 256,
    private val retryFrom: Duration = 100.milliseconds,
    private val retryUpTo: Duration = 5.seconds,
    private val tls: Tls? = null,
    private val drainWithin: Duration = 1.seconds,
) : AutoCloseable {

    /** This node in this life: a peer that sees another one under the same name knows it restarted. */
    val uid: Long = Random.nextLong()

    private val time: Clock = clock.get()
    private val closed = AtomicBoolean(false)
    private val outbound = ConcurrentHashMap<Pair<Node, Lane>, Outbound>()
    private val inbound = ConcurrentHashMap.newKeySet<Socket>()
    private val server = tls?.listening() ?: ServerSocket()
    private var accepting: Thread? = null

    /** Starts accepting peers on [self]'s host and port, 0 for any free one; the port it bound. */
    fun listen(): Int {
        server.bind(InetSocketAddress(self.host, self.port))
        accepting = Thread.ofVirtual().name("surefold-remote-accept").start(::accept)
        return server.localPort
    }

    fun send(to: Node, frame: Frame, lane: Lane = Lane.Data) {
        if (closed.get()) return listener.dropped(to, frame)
        outbound.computeIfAbsent(to to lane) { (peer, on) -> Outbound(peer, on) }.offer(frame)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.close()
        accepting?.join()
        val until = System.nanoTime() + drainWithin.inWholeNanoseconds
        outbound.values.forEach(Outbound::finish)
        outbound.values.forEach { it.close(until) }
        inbound.forEach(Socket::close)
    }

    private fun accept() {
        while (!closed.get()) {
            val socket =
                try {
                    server.accept()
                } catch (_: SocketException) {
                    return
                }
            inbound += socket
            Thread.ofVirtual().name("surefold-remote-in").start { serve(socket) }
        }
    }

    /** A peer's connection to this node: its hello, this node's answer, then its frames until it ends. */
    private fun serve(socket: Socket) {
        socket.use {
            try {
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val (peer, offered) = input.hello()
                tls?.check(socket, peer)
                // The newest both speak: a peer that offered 1 is an older surefold, and is answered as one.
                val spoken = minOf(offered, VERSION)
                output.hello(self, uid, spoken)
                while (!closed.get()) listener.received(peer, input.frame(spoken))
            } catch (_: IOException) {
                // The peer went, or sent what is not a surefold stream: either way this connection is over.
            } finally {
                inbound -= socket
            }
        }
    }

    /** One peer's queue on one lane, and the virtual thread that connects to it and writes. */
    private inner class Outbound(private val peer: Node, private val lane: Lane) {
        /**
         * The version this side offers next. An older peer closes on an offer of 2 without answering, so the next
         * attempt offers 1 straight away; once a connection on 1 has ended, the peer may have been upgraded, and 2 is
         * offered again. Declared before the writer, which reads it as soon as it starts.
         */
        private var offering = VERSION
        private val queue = ArrayBlockingQueue<Frame>(if (lane == Lane.Control) controlRoom else room)
        private val writer = Thread.ofVirtual().name("surefold-remote-out-$peer-${lane.name.lowercase()}").start(::run)

        @Volatile private var socket: Socket? = null

        // Whether the handshake is done and the writer is writing, and whether it is to stop once its queue is empty.
        @Volatile private var writing = false

        @Volatile private var finishing = false

        fun offer(frame: Frame) {
            if (!queue.offer(frame)) listener.dropped(peer, frame)
        }

        /**
         * Lets a writer that is connected write out what it holds and stop; any other is stopped now. One that is
         * connected is woken by [end] on its queue rather than an interrupt: an interrupt during a virtual thread's
         * socket write closes the socket, and the frame being written with it. A queue too full to take [end] has a
         * writer still busy, which stops on finding it empty.
         */
        fun finish() {
            finishing = true
            if (writing) queue.offer(end) else writer.interrupt()
        }

        /** Waits for the writer until [until], by [System.nanoTime], then closes whatever is left. */
        fun close(until: Long) {
            val left = until - System.nanoTime()
            if (left > 0) writer.join(left / NANOS_PER_MILLI + 1)
            socket?.close()
            writer.interrupt()
            writer.join()
            drop()
        }

        private fun run() {
            var wait = retryFrom
            while (!closed.get()) {
                val connected =
                    try {
                        connectAndWrite()
                    } catch (_: Refused) {
                        // An older peer: offered 1 at once, and nothing queued is dropped for it.
                        offering = FIRST_VERSION
                        continue
                    } catch (_: IOException) {
                        false
                    } catch (_: InterruptedException) {
                        false
                    }
                if (closed.get()) return
                if (connected) offering = VERSION
                drop()
                wait = if (connected) retryFrom else minOf(wait * 2, retryUpTo)
                try {
                    time.sleep(wait)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }

        /** Whether the handshake got through before the connection ended. */
        private fun connectAndWrite(): Boolean {
            val plain = Socket().also { socket = it }
            plain.use {
                plain.tcpNoDelay = true
                plain.connect(InetSocketAddress(peer.host, peer.port))
                val socket = tls?.over(plain, peer) ?: plain
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val (answered, spoken) = shake(output, input)
                tls?.check(socket, answered)
                listener.connected(peer, lane)
                writing = true
                // The peer never writes after its hello: a read that returns is the connection ending.
                val writer = Thread.currentThread()
                val watch =
                    Thread.ofVirtual().name("surefold-remote-watch-$peer-${lane.name.lowercase()}").start {
                        try {
                            input.read()
                        } catch (_: IOException) {
                            // Closed on either side: the writer finds out the same way.
                        }
                        if (!closed.get()) writer.interrupt()
                    }
                try {
                    write(output, spoken)
                } catch (_: IOException) {
                    // The connection ended while writing; the watch may have seen it first.
                } catch (_: InterruptedException) {
                    // The watch saw the connection end, or the transport is closing.
                } finally {
                    writing = false
                    listener.disconnected(peer, lane)
                    socket.close()
                    watch.join()
                    Thread.interrupted()
                }
            }
            return true
        }

        /** This side's hello, offering [offering], and the peer's answer: who it is, and the version both now speak. */
        private fun shake(output: DataOutputStream, input: DataInputStream): Pair<Node, Int> {
            output.hello(self, uid, offering)
            val (answered, spoken) =
                try {
                    input.hello()
                } catch (ended: EOFException) {
                    throw if (offering > FIRST_VERSION) Refused(ended) else ended
                }
            if (spoken > offering) throw IOException("$peer answered wire version $spoken to an offer of $offering")
            if (peer.name.isNotEmpty() && answered.name != peer.name) throw IOException("$peer answered as $answered")
            return answered to spoken
        }

        private fun write(output: DataOutputStream, version: Int) {
            while (true) {
                val next =
                    queue.poll()
                        ?: run {
                            output.flush()
                            // Frames can arrive during the flush, and finishing is set only after all of them have been
                            // queued, so the queue is checked once more before stopping.
                            if (finishing) queue.poll() ?: return else queue.take()
                        }
                if (next === end) return output.flush()
                output.frame(next, version)
            }
        }

        private fun drop() {
            generateSequence { queue.poll() }.filter { it !== end }.forEach { listener.dropped(peer, it) }
        }
    }
}

/** Put on a lane's queue to wake its writer as the transport closes; never written. */
private val end = Frame("", 0, ByteArray(0))

/** A peer that closed on this node's offer without answering it: an older surefold, which takes only version 1. */
private class Refused(cause: EOFException) : IOException("the peer closed on the offer of wire version $VERSION", cause)

private fun DataOutputStream.hello(self: Node, uid: Long, version: Int) {
    writeInt(MAGIC)
    writeInt(version)
    writeUTF(self.toString())
    writeLong(uid)
    flush()
}

/** The peer, and the wire version it offered or answered with. */
private fun DataInputStream.hello(): Pair<Node, Int> {
    if (readInt() != MAGIC) throw IOException("a connection that does not speak surefold")
    val version = readInt()
    if (version !in FIRST_VERSION..VERSION) {
        throw IOException("a peer on wire version $version, where this node speaks $FIRST_VERSION to $VERSION")
    }
    val node = Node.parse(readUTF())
    readLong()
    return node to version
}

/**
 * A frame's size, its path, its incarnation and, from version 2, what it carried, then its payload: whatever of the
 * size is left, so the payload is read the same way in either version.
 */
private fun DataOutputStream.frame(frame: Frame, version: Int) {
    val path = frame.path.toByteArray(Charsets.UTF_8)
    val carried = if (version >= CARRYING) carried(frame.carried) else ByteArray(0)
    writeInt(Int.SIZE_BYTES + path.size + Long.SIZE_BYTES + carried.size + frame.payload.size)
    writeInt(path.size)
    write(path)
    writeLong(frame.incarnation)
    write(carried)
    write(frame.payload)
}

private fun DataInputStream.frame(version: Int): Frame {
    val size = readInt()
    if (size !in 0..MAX_FRAME) throw IOException("a frame of $size bytes")
    val path = ByteArray(readInt()).also(::readFully)
    val incarnation = readLong()
    var read = Int.SIZE_BYTES + path.size + Long.SIZE_BYTES
    val carried =
        if (version >= CARRYING) {
            val pairs = readUnsignedShort()
            read += Short.SIZE_BYTES
            buildMap {
                repeat(pairs) {
                    val key = ByteArray(readUnsignedShort()).also(::readFully)
                    val value = ByteArray(readUnsignedShort()).also(::readFully)
                    read += 2 * Short.SIZE_BYTES + key.size + value.size
                    put(String(key, Charsets.UTF_8), String(value, Charsets.UTF_8))
                }
            }
        } else {
            emptyMap()
        }
    val payload = ByteArray(size - read).also(::readFully)
    return Frame(String(path, Charsets.UTF_8), incarnation, payload, carried = carried)
}

/**
 * [carried] as version 2 writes it: a count, then each key and value as a length and its UTF-8 bytes. A pair too long
 * for its length is left off rather than written wrong: what is carried is a trace's ids, not a payload.
 */
private fun carried(carried: Map<String, String>): ByteArray {
    val pairs =
        carried
            .map { (key, value) -> key.toByteArray(Charsets.UTF_8) to value.toByteArray(Charsets.UTF_8) }
            .filter { (k, v) -> k.size <= MAX_CARRIED && v.size <= MAX_CARRIED }
            .take(MAX_CARRIED)
    val out = ByteArrayOutputStream()
    DataOutputStream(out).use { data ->
        data.writeShort(pairs.size)
        pairs.forEach { (k, v) ->
            data.writeShort(k.size)
            data.write(k)
            data.writeShort(v.size)
            data.write(v)
        }
    }
    return out.toByteArray()
}

/** The first wire version whose frames carry a map. */
private const val CARRYING = 2

/** The most a carried count, key or value can be: each is written as an unsigned short. */
private const val MAX_CARRIED = 0xFFFF
