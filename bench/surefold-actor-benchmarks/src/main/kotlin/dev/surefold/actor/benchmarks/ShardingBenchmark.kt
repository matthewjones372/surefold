package dev.surefold.actor.benchmarks

import dev.surefold.actor.ActorRef
import dev.surefold.actor.Behaviour
import dev.surefold.actor.ask
import dev.surefold.actor.behaviour
import dev.surefold.actor.stay
import dev.surefold.cluster.sharding
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.hours
import kotlin.time.toKotlinDuration

/**
 * As `RemoteTellBenchmark`'s burst: over the 1,024 a surefold region and shard spawn their actors with, since a region
 * and an entity manager keep what a busy mailbox cannot take yet (spec 0095). Pekko's mailboxes are unbounded.
 */
private const val SHARD_BURST = 2_000

/** Ids tried before giving up on finding one a given node owns: with three nodes, the first few do. */
private const val IDS_TRIED = 200

/** No rebalancing and no passivation, as surefold's hash never moves an entity between members that stay up. */
internal const val STILL = """
pekko.cluster.sharding.rebalance-interval = 1h
pekko.cluster.sharding.passivation.strategy = none
"""

/** The count down a burst's entity makes, one per message: every node is in this JVM. */
private val bumped = AtomicReference(CountDownLatch(0))

private fun surefoldEntity(node: String): Behaviour<Shard, Unit, Nothing> = behaviour(Unit) { _, _, message ->
    when (message) {
        is Bump -> bumped.get().countDown()
        is Bounce -> message.reply(message.n)
        is Locate -> message.reply(node)
    }
    stay()
}

private fun pekkoEntity(node: String): Behavior<PekkoShard> = Behaviors.receiveMessage { message ->
    when (message) {
        is PekkoBump -> bumped.get().countDown()
        is PekkoBounce -> message.replyTo.tell(message.n)
        is PekkoLocate -> message.replyTo.tell(node)
    }
    Behaviors.same()
}

/** The first id whose entity answers that it runs on [node], asked through [locate]. */
internal fun idOwnedBy(node: String, locate: (String) -> String): String =
    (0 until IDS_TRIED).map { "entity-$it" }.firstOrNull { locate(it) == node }
        ?: error("none of $IDS_TRIED entities runs on $node")

/** The node that owns the entity measured: the one telling it (`local`), or another (`remote`). */
internal fun owner(where: String): String = when (where) {
    "local" -> nodeName(0)
    "remote" -> nodeName(1)
    else -> error("an owner is local or remote, was $where")
}

/** Three surefold nodes with the entities sharded over them, and one entity reached from the first node. */
@State(Scope.Benchmark)
open class SurefoldShards {
    @Param("local", "remote")
    var owner: String = "local"

    internal lateinit var entity: ActorRef<Shard>
    private lateinit var nodes: SurefoldTrio<*>

    @Setup(Level.Trial)
    fun start() {
        val trio = SurefoldTrio { cluster, name ->
            cluster.sharding("entity", shardCodec, passivateAfter = 1.hours) { surefoldEntity(name) }
        }
        nodes = trio
        val near = trio.parts.first()
        val id = idOwnedBy(owner(owner)) { id ->
            near.entity(id).ask<Shard, String>(ASK_WITHIN.toKotlinDuration()) { Locate(it) }.getOrNull().orEmpty()
        }
        entity = near.entity(id)
    }

    @TearDown(Level.Trial)
    fun stop() = nodes.close()
}

/** Three Pekko nodes with Cluster Sharding, and one entity reached from the first node. */
@State(Scope.Benchmark)
open class PekkoShards {
    @Param("local", "remote")
    var owner: String = "local"

    lateinit var entity: EntityRef<PekkoShard>
    private lateinit var nodes: PekkoTrio

    @Setup(Level.Trial)
    fun start() {
        nodes = PekkoTrio(STILL)
        val kind = EntityTypeKey.create(PekkoShard::class.java, "entity")
        nodes.systems.forEachIndexed { i, system ->
            ClusterSharding.get(system).init(Entity.of(kind) { pekkoEntity(nodeName(i)) })
        }
        val near = ClusterSharding.get(nodes.systems.first())
        val id = idOwnedBy(owner(owner)) { id ->
            near.entityRefFor(kind, id).ask<String>({ PekkoLocate(it) }, ASK_WITHIN).toCompletableFuture().join()
        }
        entity = near.entityRefFor(kind, id)
    }

    @TearDown(Level.Trial)
    fun stop() = nodes.close()
}

/**
 * A burst of [SHARD_BURST] tells to one sharded entity from the first node, until the entity has counted every one.
 * The row is one tell. surefold: `Sharded.entity(id).tell`, through the node's region to the owner's. Pekko:
 * `EntityRef.tell`, through the node's `ShardRegion` to the owner's.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@OperationsPerInvocation(SHARD_BURST)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class ShardedTellBenchmark {

    @Benchmark
    fun surefold(shards: SurefoldShards) = burst { n -> shards.entity.tell(Bump(n)) }

    @Benchmark
    fun pekko(shards: PekkoShards) = burst { n -> shards.entity.tell(PekkoBump(n)) }

    private fun burst(tell: (Int) -> Unit) {
        val done = CountDownLatch(SHARD_BURST)
        bumped.set(done)
        repeat(SHARD_BURST, tell)
        done.await()
    }
}

/**
 * One ask to a sharded entity from the first node, and its answer back: the round trip. surefold: `ask` on
 * `Sharded.entity(id)`, whose reply crosses as an address. Pekko: `EntityRef.ask`, whose reply goes to a temporary
 * ref.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class ShardedAskBenchmark {

    @Benchmark
    fun surefold(shards: SurefoldShards): Int =
        checkNotNull(shards.entity.ask<Shard, Int>(ASK_WITHIN.toKotlinDuration()) { Bounce(1, it) }.getOrNull())

    @Benchmark
    fun pekko(shards: PekkoShards): Int =
        shards.entity.ask<Int>({ PekkoBounce(1, it) }, ASK_WITHIN).toCompletableFuture().join()
}
