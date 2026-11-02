package dev.surefold.actor.benchmarks

import com.typesafe.config.ConfigFactory
import dev.surefold.Scope
import dev.surefold.actor.ActorRef
import dev.surefold.actor.Behaviour
import dev.surefold.actor.behaviour
import dev.surefold.actor.spawn
import dev.surefold.actor.stay
import dev.surefold.scope
import org.apache.pekko.actor.ActorSystem
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.openjdk.jmh.annotations.Scope as JmhScope

/** Larger than any burst a benchmark tells, so neither side waits on a full mailbox: Pekko's is unbounded. */
internal const val CAPACITY = 1 shl 17

/** The pool Pekko's documentation recommends for blocking work, sized as its example is. */
private const val BLOCKING_POOL = 16

/**
 * A scope held open for the whole trial on a thread of its own, since an invocation cannot sit inside one. A
 * first actor is spawned on that thread, so the scope's guardian exists before any other thread spawns.
 */
@State(JmhScope.Benchmark)
open class Surefold {

    lateinit var scope: Scope<Nothing>

    private val release = CountDownLatch(1)
    private lateinit var holder: Thread

    @Setup(Level.Trial)
    fun start() {
        val opened = AtomicReference<Scope<Nothing>>()
        val ready = CountDownLatch(1)
        holder = Thread.ofPlatform().start {
            scope {
                spawn("guardian", idle())
                opened.set(this)
                ready.countDown()
                release.await()
            }
        }
        ready.await()
        scope = opened.get()
    }

    @TearDown(Level.Trial)
    fun stop() {
        release.countDown()
        holder.join()
    }

    fun <M : Any, S> actor(name: String, behaviour: Behaviour<M, S, *>): ActorRef<M> =
        scope.spawn(name, behaviour, capacity = CAPACITY)
}

private fun idle(): Behaviour<Unit, Unit, Nothing> = behaviour(Unit) { _, _, _ -> stay() }

/** One actor system per fork, as a service has, with the blocking dispatcher Pekko's documentation shows. */
@State(JmhScope.Benchmark)
open class Pekko {

    lateinit var system: ActorSystem

    @Setup(Level.Trial)
    fun start() {
        val blocking = """
            blocking {
              type = Dispatcher
              executor = "thread-pool-executor"
              thread-pool-executor.fixed-pool-size = $BLOCKING_POOL
              throughput = 1
            }
        """.trimIndent()
        val config = ConfigFactory.parseString(blocking).withFallback(ConfigFactory.load())
        system = ActorSystem.create("surefold-actor-benchmarks", config)
    }

    @TearDown(Level.Trial)
    fun stop() {
        system.terminate()
        system.getWhenTerminated().toCompletableFuture().join()
    }
}
