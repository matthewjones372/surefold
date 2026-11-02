package io.github.matthewjones372.surefold.actor.benchmarks

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.surefold.actor.ActorRef
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.Producer
import dev.surefold.actor.ask
import dev.surefold.actor.behaviour
import dev.surefold.actor.delivered
import dev.surefold.actor.journal
import dev.surefold.actor.journal.jdbc.JdbcJournal
import dev.surefold.actor.persistent
import dev.surefold.actor.stay
import dev.surefold.cluster.Sharded
import dev.surefold.cluster.sharding
import io.github.matthewjones372.surefold.app.liquibase.migrate
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.Props
import org.apache.pekko.actor.typed.delivery.ConsumerController
import org.apache.pekko.actor.typed.delivery.DurableProducerQueue
import org.apache.pekko.actor.typed.javadsl.AskPattern
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.cluster.sharding.typed.delivery.ShardingConsumerController
import org.apache.pekko.cluster.sharding.typed.delivery.ShardingProducerController
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey
import org.apache.pekko.persistence.typed.delivery.EventSourcedProducerQueue
import org.apache.pekko.persistence.typed.javadsl.CommandHandler
import org.apache.pekko.persistence.typed.javadsl.EventHandler
import org.apache.pekko.persistence.typed.javadsl.EventSourcedBehavior
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.hours
import kotlin.time.toKotlinDuration
import org.apache.pekko.actor.typed.ActorRef as PekkoRef
import org.apache.pekko.persistence.typed.PersistenceId as PekkoPersistenceId

/** Pekko's own default pool for its journal; surefold's Hikari pool on each node is given the same. */
private const val POOL = 20

/** Counted down by the entity that handles a durable send: every node is in this JVM. */
private val handled = AtomicReference(CountDownLatch(0))

/** Applies [resource]'s DDL, found beside [owner], to [server]. */
private fun applyDdl(server: PostgresServer, owner: Class<*>, resource: String) {
    val ddl = checkNotNull(owner.getResource(resource)) { "no $resource on the classpath" }.readText()
    server.dataSource.connection.use { connection -> connection.createStatement().use { it.execute(ddl) } }
}

private fun surefoldAccount(node: String, id: String) = persistent<Account, Long, Long>(
    id = PersistenceId("account", id),
    empty = 0,
    codec = deposited,
    command = { _, balance, command ->
        when (command) {
            is Deposit -> persist(command.pence).then { after -> command.reply(after) }
            is Locate -> none().then { command.reply(node) }
        }
    },
    event = { balance, pence -> balance + pence },
)

private fun surefoldWallet(node: String) = delivered(
    behaviour<Paying, Unit>(Unit) { _, _, message ->
        when (message) {
            is Payment -> handled.get().countDown()
            is Locate -> message.reply(node)
        }
        stay()
    },
)

/** One surefold node's part: its journal's pool, the persistent accounts, and the wallets sent to reliably. */
internal class SurefoldLedgerNode(
    val pool: HikariDataSource,
    val accounts: Sharded<Account>,
    val wallets: Sharded<Paying>,
)

/**
 * Three surefold nodes on one Postgres, each through its own pool: persistent accounts, and wallets that a producer
 * on the first node sends to reliably, in memory and durably. The entities measured are reached from the first node.
 */
@State(Scope.Benchmark)
open class SurefoldLedger {
    @Param("local", "remote")
    var owner: String = "local"

    internal lateinit var account: ActorRef<Account>
    internal lateinit var wallet: String
    internal lateinit var reliable: Producer<Paying>
    internal lateinit var durable: Producer<Paying>
    private lateinit var nodes: SurefoldTrio<SurefoldLedgerNode>
    private lateinit var postgres: PostgresServer

    @Setup(Level.Trial)
    fun start() {
        postgres = PostgresServer()
        migrate(postgres.dataSource, "surefold/journal/jdbc/postgres.sql")
        nodes = SurefoldTrio { cluster, name ->
            val pool = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.url
                    username = postgres.user
                    password = postgres.password
                    maximumPoolSize = POOL
                },
            )
            journal(JdbcJournal(pool))
            SurefoldLedgerNode(
                pool,
                cluster.sharding("account", accountCodec, passivateAfter = 1.hours) { id -> surefoldAccount(name, id) },
                cluster.sharding("wallet", payingCodec, passivateAfter = 1.hours) { surefoldWallet(name) },
            )
        }
        val near = nodes.parts.first()
        val within = ASK_WITHIN.toKotlinDuration()
        account = near.accounts.entity(
            idOwnedBy(owner(owner)) { id ->
                near.accounts.entity(id).ask<Account, String>(within) { Locate(it) }.getOrNull().orEmpty()
            },
        )
        wallet = idOwnedBy(owner(owner)) { id ->
            near.wallets.entity(id).ask<Paying, String>(within) { Locate(it) }.getOrNull().orEmpty()
        }
        reliable = near.wallets.reliable("reliable")
        durable = near.wallets.reliable("durable", durable = true)
    }

    @TearDown(Level.Trial)
    fun stop() {
        nodes.close()
        nodes.parts.forEach { it.pool.close() }
        postgres.close()
    }
}

/** An account remembered by its deposits, as `surefoldAccount` is. */
class PekkoAccountEntity(id: PekkoPersistenceId, private val node: String) :
    EventSourcedBehavior<PekkoAccount, PekkoDeposited, Long>(id) {

    override fun emptyState(): Long = 0

    override fun commandHandler(): CommandHandler<PekkoAccount, PekkoDeposited, Long> =
        newCommandHandlerBuilder().forAnyState()
            .onCommand(PekkoDeposit::class.java) { _, deposit ->
                Effect().persist(PekkoDeposited(deposit.pence)).thenReply(deposit.replyTo) { after -> after }
            }
            .onCommand(PekkoLocate::class.java) { _, locate -> Effect().reply(locate.replyTo, node) }
            .build()

    override fun eventHandler(): EventHandler<Long, PekkoDeposited> =
        newEventHandlerBuilder().forAnyState()
            .onEvent(PekkoDeposited::class.java) { balance, paid -> balance + paid.pence }
            .build()
}

/** A wallet behind Pekko's consumer controller: it confirms each command once handled, as `delivered` does. */
private fun pekkoWallet(
    node: String,
    start: PekkoRef<ConsumerController.Start<PekkoPaying>>,
): Behavior<ConsumerController.Delivery<PekkoPaying>> = Behaviors.setup { ctx ->
    start.tell(ConsumerController.Start(ctx.self))
    Behaviors.receiveMessage { delivery ->
        when (val message = delivery.message()) {
            is PekkoPay -> handled.get().countDown()
            is PekkoLocate -> message.replyTo.tell(node)
        }
        delivery.confirmTo().tell(ConsumerController.confirmed())
        Behaviors.same()
    }
}

/**
 * A `ShardingProducerController` on [system], and the actor that takes its demand: each `RequestNext` is room for one
 * command, which [next] hands to the benchmark's thread.
 */
internal class PekkoProducer(
    system: ActorSystem<*>,
    region: PekkoRef<ShardingEnvelope<ConsumerController.SequencedMessage<PekkoPaying>>>,
    id: String,
    queue: Behavior<DurableProducerQueue.Command<PekkoPaying>>?,
) {
    private val demand = LinkedBlockingQueue<ShardingProducerController.RequestNext<PekkoPaying>>()

    init {
        val controller = system.systemActorOf(
            ShardingProducerController.create(PekkoPaying::class.java, id, region, Optional.ofNullable(queue)),
            "producer-$id",
            Props.empty(),
        )
        val producer = system.systemActorOf(
            Behaviors.receiveMessage<ShardingProducerController.RequestNext<PekkoPaying>> { next ->
                demand.put(next)
                Behaviors.same()
            },
            "demand-$id",
            Props.empty(),
        )
        controller.tell(ShardingProducerController.Start(producer))
    }

    fun next(): ShardingProducerController.RequestNext<PekkoPaying> =
        checkNotNull(demand.poll(ASK_WITHIN.toMillis(), TimeUnit.MILLISECONDS)) { "the producer asked for nothing" }
}

/** Pekko Persistence JDBC on [server], through Slick's Hikari pool, which is [POOL] connections by default. */
private fun journalOn(server: PostgresServer) = """
    pekko.persistence.journal.plugin = "jdbc-journal"
    pekko.persistence.snapshot-store.plugin = "jdbc-snapshot-store"
    pekko-persistence-jdbc.shared-databases.slick {
      profile = "slick.jdbc.PostgresProfile${'$'}"
      db {
        url = "${server.url}"
        driver = "org.postgresql.Driver"
        user = "${server.user}"
        password = "${server.password}"
        numThreads = $POOL
        maxConnections = $POOL
        minConnections = $POOL
      }
    }
    jdbc-journal.use-shared-db = "slick"
    jdbc-snapshot-store.use-shared-db = "slick"
    jdbc-read-journal.use-shared-db = "slick"
""".trimIndent()

/**
 * Three Pekko nodes on one Postgres, each through its own Slick pool: persistent accounts, and wallets that a
 * `ShardingProducerController` on the first node sends to, with and without a durable queue.
 */
@State(Scope.Benchmark)
open class PekkoLedger {
    @Param("local", "remote")
    var owner: String = "local"

    lateinit var account: EntityRef<PekkoAccount>
    lateinit var wallet: String
    internal lateinit var reliable: PekkoProducer
    internal lateinit var durable: PekkoProducer
    lateinit var system: ActorSystem<*>
    private lateinit var nodes: PekkoTrio
    private lateinit var postgres: PostgresServer

    @Setup(Level.Trial)
    fun start() {
        postgres = PostgresServer()
        applyDdl(postgres, EventSourcedProducerQueue::class.java, "/schema/postgres/postgres-create-schema.sql")
        nodes = PekkoTrio(STILL + journalOn(postgres))
        system = nodes.systems.first()
        val accounts = EntityTypeKey.create(PekkoAccount::class.java, "account")

        @Suppress("UNCHECKED_CAST")
        val wallets = EntityTypeKey.create(
            ConsumerController.SequencedMessage::class.java as Class<ConsumerController.SequencedMessage<PekkoPaying>>,
            "wallet",
        )
        val regions = nodes.systems.mapIndexed { i, node ->
            val sharding = ClusterSharding.get(node)
            val name = nodeName(i)
            sharding.init(
                Entity.of(accounts) { PekkoAccountEntity(PekkoPersistenceId.of("account", it.entityId), name) },
            )
            sharding.init(
                Entity.of(wallets) { ShardingConsumerController.create { start -> pekkoWallet(name, start) } },
            )
        }
        val near = ClusterSharding.get(system)
        val id = idOwnedBy(owner(owner)) { id ->
            near.entityRefFor(accounts, id).ask<String>({ PekkoLocate(it) }, ASK_WITHIN).toCompletableFuture().join()
        }
        account = near.entityRefFor(accounts, id)
        reliable = PekkoProducer(system, regions.first(), "reliable", null)
        durable = PekkoProducer(
            system,
            regions.first(),
            "durable",
            EventSourcedProducerQueue.create(PekkoPersistenceId.ofUniqueId("durable-producer")),
        )
        wallet = idOwnedBy(owner(owner)) { id ->
            AskPattern.ask(
                reliable.next().sendNextTo(),
                { reply: PekkoRef<String> -> ShardingEnvelope(id, PekkoLocate(reply) as PekkoPaying) },
                ASK_WITHIN,
                system.scheduler(),
            ).toCompletableFuture().join()
        }
    }

    @TearDown(Level.Trial)
    fun stop() {
        nodes.close()
        postgres.close()
    }
}

/**
 * One command to a persistent entity from the first node, which writes one event and answers once it is written: an
 * append and its round trip. surefold: `ask` on a sharded `persistent`, answering in `then`, on `JdbcJournal`. Pekko:
 * `EntityRef.ask` on a sharded `EventSourcedBehavior`, answering in `thenReply`, on Persistence JDBC.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class PersistentAppendBenchmark {

    @Benchmark
    fun surefold(ledger: SurefoldLedger): Long =
        checkNotNull(ledger.account.ask<Account, Long>(ASK_WITHIN.toKotlinDuration()) { Deposit(1, it) }.getOrNull())

    @Benchmark
    fun pekko(ledger: PekkoLedger): Long =
        ledger.account.ask<Long>({ PekkoDeposit(1, it) }, ASK_WITHIN).toCompletableFuture().join()
}

/**
 * One command sent reliably from the first node to an in-memory entity, until the producer has its confirmation.
 * surefold: `reliable(...).send`, then `drain` for the confirmation. Pekko: `ShardingProducerController`'s
 * `MessageWithConfirmation`, whose `Done` comes once the consumer's confirmation reaches the producer.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class ReliableSendBenchmark {

    @Benchmark
    fun surefold(ledger: SurefoldLedger) {
        checkNotNull(ledger.reliable.send(ledger.wallet) { Payment(1, it) }.getOrNull()) { "the producer was full" }
        check(ledger.reliable.drain(ASK_WITHIN.toKotlinDuration())) { "no confirmation came" }
    }

    @Benchmark
    fun pekko(ledger: PekkoLedger): Done = AskPattern.ask(
        ledger.reliable.next().askNextTo(),
        { done: PekkoRef<Done> ->
            ShardingProducerController.MessageWithConfirmation(ledger.wallet, PekkoPay(1) as PekkoPaying, done)
        },
        ASK_WITHIN,
        ledger.system.scheduler(),
    ).toCompletableFuture().join()
}

/**
 * One command sent durably from the first node to an in-memory entity, until the entity has handled it. Each side's
 * producer writes the command to its journal before sending it, and a confirmation after. surefold:
 * `reliable(durable = true).send`. Pekko: `ShardingProducerController` with an `EventSourcedProducerQueue`. The row
 * ends at the entity, since Pekko answers a durable send once it is stored, not once it is confirmed.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class DurableSendBenchmark {

    @Benchmark
    fun surefold(ledger: SurefoldLedger) = awaitHandled {
        checkNotNull(ledger.durable.send(ledger.wallet) { Payment(1, it) }.getOrNull()) { "the producer was full" }
    }

    @Benchmark
    fun pekko(ledger: PekkoLedger) = awaitHandled {
        ledger.durable.next().sendNextTo().tell(ShardingEnvelope(ledger.wallet, PekkoPay(1)))
    }

    private fun awaitHandled(send: () -> Unit) {
        val done = CountDownLatch(1)
        handled.set(done)
        send()
        check(done.await(ASK_WITHIN.toMillis(), TimeUnit.MILLISECONDS)) { "the entity never handled the send" }
    }
}
