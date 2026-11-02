package dev.surefold.actor.benchmarks

import com.typesafe.config.ConfigFactory
import dev.surefold.Scope
import dev.surefold.actor.ActorRef
import dev.surefold.actor.Address
import dev.surefold.actor.Reply
import dev.surefold.actor.ask
import dev.surefold.actor.behaviour
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.RemoteNode
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.remote.node
import dev.surefold.actor.spawn
import dev.surefold.actor.stay
import dev.surefold.scope
import org.apache.pekko.actor.AbstractActor
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Props
import org.apache.pekko.pattern.Patterns
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.toKotlinDuration
import org.apache.pekko.actor.ActorRef as PekkoRef
import org.openjdk.jmh.annotations.Scope as JmhScope

/** Under Pekko's outbound queue of 3,072 and surefold's of 8,192, so that neither side drops part of a burst. */
private const val BURST = 2_000

/** Far past any round trip on loopback: an ask that takes this long is a benchmark that has broken. */
internal val ASK_WITHIN: Duration = Duration.ofSeconds(10)

internal fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** The count down a burst's receiver makes, one per message: both nodes are in this JVM. */
private val counted = AtomicReference(CountDownLatch(0))

internal sealed interface Wired

internal data class Echo(val n: Int, val reply: Reply<Int>) : Wired

internal data class Count(val n: Int) : Wired

private val wiredCodec = object : MessageCodec<Wired> {
    override fun write(message: Wired, out: WireOut) = when (message) {
        is Echo -> {
            out.int(1)
            out.int(message.n)
            out.reply(message.reply, Codecs.int)
        }

        is Count -> {
            out.int(2)
            out.int(message.n)
        }
    }

    override fun read(input: WireIn): Wired = when (input.int()) {
        1 -> Echo(input.int(), input.reply(Codecs.int))
        else -> Count(input.int())
    }
}

/** A scope held open for the trial on a thread of its own, as a node. */
private class HeldNode(name: String, port: Int) {
    lateinit var scope: Scope<Nothing>
    lateinit var node: RemoteNode
    private val release = CountDownLatch(1)
    private val holder: Thread

    init {
        val ready = CountDownLatch(1)
        holder = Thread.ofPlatform().start {
            scope {
                scope = this
                node = node(name, port)
                ready.countDown()
                release.await()
            }
        }
        ready.await()
    }

    fun close() {
        release.countDown()
        holder.join()
    }
}

/** Two surefold nodes on loopback: an echo and a counter exposed on one, reached from the other. */
@State(JmhScope.Benchmark)
open class SurefoldNodes {
    internal lateinit var echo: ActorRef<Wired>
    private lateinit var near: HeldNode
    private lateinit var far: HeldNode

    @Setup(Level.Trial)
    fun start() {
        val farPort = freePort()
        far = HeldNode("far", farPort)
        near = HeldNode("near", freePort())
        val served = far.scope.spawn(
            "served",
            behaviour<Wired, Unit>(Unit) { _, _, message ->
                when (message) {
                    is Echo -> message.reply(message.n)
                    is Count -> counted.get().countDown()
                }
                stay()
            },
            capacity = CAPACITY,
        )
        far.node.expose(served, wiredCodec)
        echo = near.node.remote(Address("far@127.0.0.1:$farPort", "/user/served", 0), wiredCodec)
    }

    @TearDown(Level.Trial)
    fun stop() {
        near.close()
        far.close()
    }
}

/** Answers each number with itself, to whoever sent it. */
class PekkoEcho : AbstractActor() {
    override fun createReceive(): Receive =
        receiveBuilder().match(Int::class.javaObjectType) { sender.tell(it, self) }.build()
}

/** Counts down the burst's latch once for each number. */
class PekkoCounter : AbstractActor() {
    override fun createReceive(): Receive =
        receiveBuilder().match(Int::class.javaObjectType) { counted.get().countDown() }.build()
}

/** Two Pekko systems on loopback with Artery over TCP, its documented remote transport without Aeron. */
@State(JmhScope.Benchmark)
open class PekkoNodes {
    lateinit var echo: PekkoRef
    lateinit var counter: PekkoRef
    private lateinit var near: ActorSystem
    private lateinit var far: ActorSystem

    private fun system(name: String, port: Int): ActorSystem = ActorSystem.create(
        name,
        ConfigFactory.parseString(
            """
            pekko.loglevel = WARNING
            pekko.actor.provider = remote
            pekko.remote.artery.transport = tcp
            pekko.remote.artery.canonical.hostname = "127.0.0.1"
            pekko.remote.artery.canonical.port = $port
            """.trimIndent(),
        ).withFallback(ConfigFactory.load()),
    )

    @Setup(Level.Trial)
    fun start() {
        val farPort = freePort()
        far = system("far", farPort)
        near = system("near", freePort())
        far.actorOf(Props.create(PekkoEcho::class.java), "echo")
        far.actorOf(Props.create(PekkoCounter::class.java), "counter")
        val timeout = ASK_WITHIN
        echo = near.actorSelection("pekko://far@127.0.0.1:$farPort/user/echo").resolveOne(timeout)
            .toCompletableFuture().join()
        counter = near.actorSelection("pekko://far@127.0.0.1:$farPort/user/counter").resolveOne(timeout)
            .toCompletableFuture().join()
    }

    @TearDown(Level.Trial)
    fun stop() {
        listOf(near, far).forEach { it.terminate() }
        listOf(near, far).forEach { it.getWhenTerminated().toCompletableFuture().join() }
    }
}

/** One ask to an actor on another node on loopback, and its answer back: the round trip. */
@State(JmhScope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class RemoteAskBenchmark {

    @Benchmark
    fun surefold(nodes: SurefoldNodes): Int =
        checkNotNull(nodes.echo.ask(ASK_WITHIN.toKotlinDuration()) { Echo(1, it) }.getOrNull())

    @Benchmark
    fun pekko(nodes: PekkoNodes): Any =
        Patterns.ask(nodes.echo, 1, ASK_WITHIN).toCompletableFuture().join()
}

/** A burst of [BURST] tells to an actor on another node, until it has counted every one. The row is one tell. */
@State(JmhScope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@OperationsPerInvocation(BURST)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class RemoteTellBenchmark {

    @Benchmark
    fun surefold(nodes: SurefoldNodes) = burst { n -> nodes.echo.tell(Count(n)) }

    @Benchmark
    fun pekko(nodes: PekkoNodes) = burst { n -> nodes.counter.tell(n, PekkoRef.noSender()) }

    private fun burst(tell: (Int) -> Unit) {
        val done = CountDownLatch(BURST)
        counted.set(done)
        repeat(BURST, tell)
        done.await()
    }
}
