# Remoting and clusters

`surefold-actor` runs actors in one process, which the
[actors guide](actors.md) covers. This page is what comes after: a
second node, then a cluster of them, then entities spread across it, state
that survives a move, commands that must arrive, and topics. It is ordered the
way a service meets these things.

Every complete example below is compiled against the library by
`GuideExampleTest`, so what you read is what builds. Each is written as an
extension on the scope your service already has, since where that scope comes
from, `scope { }` or `surefold-app-actor`'s `Actors.within`, is the service's
business.

```kotlin
dependencies {
    implementation("dev.surefold:surefold-actor-remote:0.18.0")       // two nodes
    implementation("dev.surefold:surefold-cluster:0.18.0")            // membership, sharding, delivery, topics
    implementation("dev.surefold:surefold-actor-journal-jdbc:0.18.0") // a journal every node reaches
    implementation("dev.surefold:surefold-actor-projection:0.18.0")   // read models
    implementation("dev.surefold:surefold-cluster-saga:0.18.0")       // sagas that outlive their node
}
```

1. [Two nodes](#two-nodes)
2. [A cluster](#a-cluster)
3. [Entities](#entities)
4. [State that survives](#state-that-survives)
5. [Commands that must arrive](#commands-that-must-arrive)
6. [A saga that outlives its node](#a-saga-that-outlives-its-node)
7. [Stopping, watching and telling everyone](#stopping-watching-and-telling-everyone)

## Two nodes

A node is a scope that listens on a port, on loopback unless you give it a
`host`. An actor becomes reachable from other nodes when you expose it with a
codec, and another node reaches it through a ref made from its address. Tell
and ask work as they do in one process, with one difference: across a network
a message may be lost, so a remote tell is at most once.

- **Connections.** Nodes talk over TCP on JDK sockets and virtual threads. A
  node opens its own outbound connections to each peer, one for control
  traffic and one for messages, so order holds for each sender, receiver and
  lane.
- **Loss.** Frames for a peer queue up to a bound (8,192 frames). Past that,
  or while the peer is unreachable, they are dropped and become dead letters
  on the sending side. A message for a path or incarnation that no longer
  exists becomes a dead letter on the receiving node.
- **Watching.** A watch on a remote actor ends when the actor stops or once
  its node has been unreachable for `unreachableAfter` (10 seconds by
  default); in a cluster, membership decides instead.

For commands that must arrive, see
[Commands that must arrive](#commands-that-must-arrive).

No serialisation library is chosen for you. A `MessageCodec` writes a message
field by field; a `Reply` or an `ActorRef` inside a message crosses as an
address, so an ask made on one node is answered from another. Three modules in
[surefold-integrations](https://github.com/surefold/surefold-integrations) build codecs from classes you already
have instead:

- `surefold-actor-remote-protobuf`: a codec for a generated message, `oneOf` for a
  protocol of several messages under fixed tags, and `asked` for an ask whose
  request is one.
- `surefold-actor-remote-avro`: a codec for a specific record in single-object
  encoding, read across its versions through a `SchemaStore`, and `asked`.
- `surefold-actor-remote-kotlinx`: codecs for messages, events and snapshot state
  from `@Serializable` classes, in ProtoBuf by default, `oneOf`, `asked`, and
  `proto()` for the `.proto` schema the classes produce. kotlinx.serialization
  marks its ProtoBuf format experimental.

<!-- cluster-remote -->
```kotlin
import dev.surefold.Scope
import dev.surefold.actor.Address
import dev.surefold.actor.AskFailure
import dev.surefold.actor.Reply
import dev.surefold.actor.ask
import dev.surefold.actor.become
import dev.surefold.actor.behaviour
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.remote.node
import dev.surefold.actor.spawn
import dev.surefold.actor.stay
import dev.surefold.fail.Either
import kotlin.time.Duration.Companion.seconds

sealed interface Stock

data class Receive(val sku: String, val count: Int) : Stock

data class Count(val sku: String, val reply: Reply<Int>) : Stock

object StockCodec : MessageCodec<Stock> {
    override fun write(message: Stock, out: WireOut) = when (message) {
        is Receive -> {
            out.int(1)
            out.string(message.sku)
            out.int(message.count)
        }

        is Count -> {
            out.int(2)
            out.string(message.sku)
            out.reply(message.reply, Codecs.int)
        }
    }

    override fun read(input: WireIn): Stock = when (val tag = input.int()) {
        1 -> Receive(input.string(), input.int())
        2 -> Count(input.string(), input.reply(Codecs.int))
        else -> error("no stock message has the tag $tag")
    }
}

fun stock() = behaviour<Stock, Map<String, Int>>(emptyMap()) { _, counts, message ->
    when (message) {
        is Receive -> become(counts + (message.sku to (counts[message.sku] ?: 0) + message.count))
        is Count -> stay().also { message.reply(counts[message.sku] ?: 0) }
    }
}

/** On the warehouse node: the stock actor, where other nodes can reach it. */
fun Scope<Nothing>.warehouse() {
    val node = node("warehouse", 25520, host = "10.0.0.7")
    node.expose(spawn("stock", stock()), StockCodec)
}

/** On a shop node: a ref to the warehouse's stock, told and asked as if it were here. */
fun Scope<Nothing>.shop(): Either<AskFailure, Int> {
    val node = node("shop-1", 25520, host = "10.0.0.8")
    val warehouse = Node("warehouse", "10.0.0.7", 25520)
    val stock = node.remote(Address(warehouse.toString(), "/user/stock", 0), StockCodec)
    stock.tell(Receive("sku-1", 12))
    return stock.ask(5.seconds) { Count("sku-1", it) }
}
```

An address names a node, a path and an incarnation; an incarnation of `0`
means whichever actor is at that path when the message arrives.

### Nodes that know each other

A node listens on loopback by default, and speaks plain TCP. To run nodes on a
network you do not wholly trust, give each one a `Tls`: every connection, both
ways, is TLS 1.3 with a certificate on each side, and a peer's certificate
must name it, as a DNS subject alternative name, by the node name it gives. A node whose
certificate another CA signed, or that claims another node's name, is refused
before it can send anything.

<!-- cluster-tls -->
```kotlin
import dev.surefold.Scope
import dev.surefold.actor.remote.RemoteNode
import dev.surefold.actor.remote.Tls
import dev.surefold.actor.remote.node
import java.io.File
import java.security.KeyStore

/** This node's key and certificate, and the CA that signed every node's, from PKCS12 files the platform mounts. */
fun Scope<Nothing>.secured(password: CharArray): RemoteNode {
    fun store(path: String) =
        KeyStore.getInstance("PKCS12").apply { File(path).inputStream().use { load(it, password) } }
    val tls = Tls.mutual(keys = store("/etc/surefold/node.p12"), password = password, trusted = store("/etc/surefold/ca.p12"))
    return node("shop-1", 25520, host = "10.0.0.8", tls = tls)
}
```

`Tls(context)` takes an `SSLContext` you built yourself, for a store
`Tls.mutual` does not read; the name check is the same.

## A cluster

A cluster is nodes that agree who is up. `cluster(node, discovery)` joins the
nodes that `discovery` finds, or forms a cluster if this node is the lowest
seed and nobody answers. Membership is agreed by gossip, with no coordinator.

- **Seeds.** `Discovery.static` for a fixed list, `Discovery.dns` for every
  address a name resolves to, and `Discovery.srv` for SRV records.
  surefold-integrations' `surefold-cluster-kubernetes` finds seeds through the pods API, and
  `surefold-cluster-aws` through Cloud Map or ECS.
- **The view.** `cluster.view` is this node's current view: every member with
  its status, the ones some member cannot reach, and the leader. A member is
  `Joining`, `Up`, `Leaving`, `Down` or `Removed`.
- **Events.** `cluster.subscribe(ref)` tells an actor the view as it is, then
  each change: a member `Up`, `Unreachable`, `Reachable`, `Downed` or
  `Removed`.
- **Partitions.** A member that stops answering is unreachable, and nothing
  moves on until it is reachable again or downed. `Downing` decides which side
  of a partition stays: `keepMajority` by default, `staticQuorum` for a fixed
  size, or `lease` for two nodes or an even split, where a majority cannot
  decide. `stableAfter` is how long the partition must hold still first.
- **Restarts.** A node that restarts at the same address is a new life of it,
  with a new `cluster.uid`. The earlier life is downed as the new one joins,
  then removed, and the new life hears both, under its own address.
  `cluster.isSelf(member)` tells this life from an earlier one, so compare
  with it, not with `cluster.self`. A watch ends with the life it was made on.
- **Ready.** `cluster.ready()` is true while this node is `Up`, has a leader,
  and can reach every member it sees. It is the answer to `/ready`.

<!-- cluster-membership -->
```kotlin
import dev.surefold.Scope
import dev.surefold.actor.behaviour
import dev.surefold.actor.remote.node
import dev.surefold.actor.spawn
import dev.surefold.actor.stay
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.Discovery
import dev.surefold.cluster.Downing
import dev.surefold.cluster.MemberEvent
import dev.surefold.cluster.cluster
import dev.surefold.logInfo
import kotlin.time.Duration.Companion.seconds

/** A node of the shop's cluster, whose seeds are every address the headless service's name resolves to. */
fun Scope<Nothing>.joinShop(): Cluster {
    val node = node("shop-1", 25520, host = "0.0.0.0")
    val cluster = cluster(
        node,
        Discovery.dns("shop.default.svc.cluster.local", port = 25520),
        downing = Downing.keepMajority(stableAfter = 20.seconds),
    )
    val watcher = spawn(
        "membership-log",
        behaviour<MemberEvent, Unit>(Unit) { _, _, event ->
            // isSelf, not the address: a node restarted where it was hears its earlier life downed at its own.
            if (event is MemberEvent.Downed && cluster.isSelf(event.member)) logInfo("cluster: this node was downed")
            stay().also { logInfo("cluster: $event") }
        },
    )
    cluster.subscribe(watcher)
    return cluster
}
```

`Gossiping` sets how often members probe each other and how long an answer may
take. The defaults suit a busy node on a real network; tests that want a
cluster in a second set them lower, and a node under heavy load with them set
too low will see live members as unreachable.

### Joined from config

`surefold-app-cluster` makes the cluster a node of an application's graph,
joined as a HOCON section says. It reads Typesafe Config, so it lives in
surefold-integrations, and its guide, with the section in full, is
[a cluster joined from config](https://github.com/surefold/surefold-integrations/blob/main/docs/configuration.md#a-cluster-joined-from-config).

## Entities

An entity is an actor with an id, run on whichever member owns it.
`cluster.sharding(kind, codec, passivateAfter) { id -> behaviour }` spreads a
kind's ids over 256 shards and each shard over the `Up` members, by a hash
every node computes the same way, so there is no coordinator to ask.
`entity(id)` is a ref that stays good while the entity moves: tell it from any
node and the message reaches the owner. An entity that has had nothing for
`passivateAfter` stops, and starts again on its next message.

When a member joins or goes, only the shards it gains or loses move. The node
that wins a shard waits until the one that had it has stopped it, so an entity
never runs on two nodes at once. What arrives meanwhile is kept, up to a
bound, and delivered once the shard settles.

Shards spread evenly by count, not by work. A kind whose ids are skewed can
ask its shards to follow the load with
`rebalance = Rebalance.byLoad(every = 1.minutes, tolerance = 0.2, mostMoves = 4)`. Each member
gossips what it runs, and every `every` the leader moves up to `mostMoves` of
the busiest member's shards to the least-loaded members, while it is over the
mean by more than `tolerance`. Load is messages handled where there are any,
and running entities otherwise. A moved shard goes through the same handoff,
stays put for three intervals, and goes back to its hash owner if the member
it moved to leaves. A kind that does not ask stays where the hash puts it.

A singleton is one actor in the whole cluster, on the oldest `Up` member, and
moves by the same handoff when that member goes.

Every node calls `sharding` and `singleton` with the same arguments, including
nodes that should never host them: give the nodes that should host them a role
the others lack, and place by it.

<!-- cluster-entities -->
```kotlin
import dev.surefold.Scope
import dev.surefold.actor.ActorRef
import dev.surefold.actor.Reply
import dev.surefold.actor.ask
import dev.surefold.actor.become
import dev.surefold.actor.behaviour
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.remote.node
import dev.surefold.actor.stay
import dev.surefold.cluster.Discovery
import dev.surefold.cluster.Sharded
import dev.surefold.cluster.cluster
import dev.surefold.cluster.sharding
import dev.surefold.cluster.singleton
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

sealed interface Basket

data class Put(val sku: String) : Basket

data class Contents(val reply: Reply<List<String>>) : Basket

object BasketCodec : MessageCodec<Basket> {
    override fun write(message: Basket, out: WireOut) = when (message) {
        is Put -> {
            out.int(1)
            out.string(message.sku)
        }

        is Contents -> {
            out.int(2)
            out.reply(message.reply, Codecs.list(Codecs.string))
        }
    }

    override fun read(input: WireIn): Basket = when (val tag = input.int()) {
        1 -> Put(input.string())
        2 -> Contents(input.reply(Codecs.list(Codecs.string)))
        else -> error("no basket message has the tag $tag")
    }
}

fun basket() = behaviour<Basket, List<String>>(emptyList()) { _, skus, message ->
    when (message) {
        is Put -> become(skus + message.sku)
        is Contents -> stay().also { message.reply(skus) }
    }
}

/** Runs on every node of the shop: baskets on the nodes that serve them, one daily report in the whole cluster. */
fun Scope<Nothing>.shopFloor(roles: Set<String>): Pair<Sharded<Basket>, ActorRef<Basket>> {
    val node = node("shop-1", 25520, host = "0.0.0.0")
    val cluster = cluster(node, Discovery.srv("_surefold._tcp.shop.internal"), roles = roles)
    val baskets = cluster.sharding("basket", BasketCodec, passivateAfter = 10.minutes, role = "baskets") { basket() }
    val report = cluster.singleton("daily-report", BasketCodec) { basket() }
    return baskets to report
}

/** From any node, whether or not it hosts baskets. */
fun addAndRead(baskets: Sharded<Basket>) =
    baskets.entity("customer-42").run {
        tell(Put("sku-1"))
        ask(5.seconds) { Contents(it) }
    }
```

An entity here loses its state when it moves or passivates. To keep it, make
the entity persistent: the next section.

## State that survives

A persistent behaviour is remembered by its events. A command answers an
effect: `persist` writes events to the scope's journal and then applies them,
`none` writes nothing, and `then` runs once they are written. On start, and on
every move, the entity replays its events before its first command. Two writers for
one entity cannot both succeed: the second append is a conflict, which becomes the
entity's failure for its supervision to decide.

For a cluster the journal must be one every node reaches. `JdbcJournal` keeps
events in one table over the `DataSource` you give it, and ships its tables in
the jar as a Liquibase changelog in formatted SQL, `surefold/journal/jdbc/postgres.sql`,
for your changelog to include (or surefold-integrations' `surefold-app-liquibase`'s
`migrate` to run); nothing creates tables at start.

- **Snapshots.** With `snapshots = every(100, codec)` the entity saves its state
  after each hundredth event, and a start replays only what came after the
  newest snapshot.
- **Pruning.** Give `every` a `Prune` and the events a snapshot covers are
  deleted: `Prune.always`, or `Prune.after(offsets, "name", …)` to wait until
  every named read model has read them.
- **Read models.** `Projection.follow` is every event of one kind, across
  entities, in one order, as a stream on any surefold-stream backend.
  `runProjecting()` saves each event's offset once its work is done, so a read
  model started again goes on where it left off. Delivery is at least once:
  write handlers that are safe to repeat, keyed by the entity and the event's
  sequence number.

<!-- cluster-persistent -->
```kotlin
import dev.surefold.Scope
import dev.surefold.actor.EventCodec
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.Prune
import dev.surefold.actor.Reply
import dev.surefold.actor.StateCodec
import dev.surefold.actor.every
import dev.surefold.actor.journal
import dev.surefold.actor.journal.jdbc.JdbcJournal
import dev.surefold.actor.journal.jdbc.JdbcOffsets
import dev.surefold.actor.journal.jdbc.JdbcSnapshots
import dev.surefold.actor.persistent
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.remote.node
import dev.surefold.actor.snapshots
import dev.surefold.cluster.Discovery
import dev.surefold.cluster.Sharded
import dev.surefold.cluster.cluster
import dev.surefold.cluster.sharding
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes

sealed interface Account

data class Deposit(val pence: Long) : Account

data class Balance(val reply: Reply<Long>) : Account

object AccountCodec : MessageCodec<Account> {
    override fun write(message: Account, out: WireOut) = when (message) {
        is Deposit -> {
            out.int(1)
            out.long(message.pence)
        }

        is Balance -> {
            out.int(2)
            out.reply(message.reply, Codecs.long)
        }
    }

    override fun read(input: WireIn): Account = when (val tag = input.int()) {
        1 -> Deposit(input.long())
        2 -> Balance(input.reply(Codecs.long))
        else -> error("no account message has the tag $tag")
    }
}

/** The one event: pence paid in, as its decimal text. */
object Deposited : EventCodec<Long> {
    override fun encode(event: Long): ByteArray = event.toString().toByteArray()

    override fun decode(bytes: ByteArray): Long = String(bytes).toLong()
}

object Pence : StateCodec<Long> {
    override fun encode(state: Long): ByteArray = state.toString().toByteArray()

    override fun decode(bytes: ByteArray): Long = String(bytes).toLong()
}

/** An account remembered by its deposits, snapshotted every hundred, pruned once the ledger read model has read. */
fun account(id: String, offsets: JdbcOffsets) = persistent<Account, Long, Long>(
    id = PersistenceId("account", id),
    empty = 0,
    codec = Deposited,
    snapshots = every(100, Pence, prune = Prune.after(offsets, "ledger")),
    command = { _, balance, command ->
        when (command) {
            is Deposit -> persist(command.pence)
            is Balance -> none().then { command.reply(balance) }
        }
    },
    event = { balance, deposited -> balance + deposited },
)

/** Every node of the bank: one Postgres every node reaches, holding the journal, the snapshots and the offsets. */
fun Scope<Nothing>.bank(database: DataSource): Sharded<Account> {
    journal(JdbcJournal(database))
    snapshots(JdbcSnapshots(database))
    val offsets = JdbcOffsets(database)
    val cluster = cluster(node("bank-1", 25520, host = "0.0.0.0"), Discovery.srv("_surefold._tcp.bank.internal"))
    return cluster.sharding("account", AccountCodec, passivateAfter = 5.minutes) { id -> account(id, offsets) }
}
```

The ledger the account above waits for is a read model: one per service, run
by one node, a singleton being the natural place to start it.

<!-- cluster-read-model -->
```kotlin
import dev.surefold.actor.EventCodec
import dev.surefold.actor.journal.jdbc.JdbcJournal
import dev.surefold.actor.journal.jdbc.JdbcOffsets
import dev.surefold.actor.projection.Projection
import dev.surefold.actor.projection.mapFollowed
import dev.surefold.actor.projection.runProjecting
import dev.surefold.stream.Forks
import dev.surefold.stream.Running
import dev.surefold.stream.start
import javax.sql.DataSource

object Deposits : EventCodec<Long> {
    override fun encode(event: Long): ByteArray = event.toString().toByteArray()

    override fun decode(bytes: ByteArray): Long = String(bytes).toLong()
}

/** Where the ledger writes: keyed by account and sequence number, so a deposit handled twice is written once. */
fun interface Ledger {
    fun record(account: String, sequence: Long, pence: Long)
}

/** Every deposit of every account, from the one after the last the ledger saved, until the run is stopped. */
fun ledger(database: DataSource, ledger: Ledger): Running<Nothing, Long> {
    val offsets = JdbcOffsets(database)
    val deposits = Projection.follow(JdbcJournal(database), "account", Deposits, offsets, name = "ledger")
    return deposits
        .mapFollowed { deposit -> ledger.record(deposit.id.id, deposit.sequence, deposit.value) }
        .runProjecting()
        .start(Forks())
}
```

## A journal across databases

One database takes every write from every node. When that is the limit, split
the journal by entity across several:

- **Each id lives in one database.** Its slice is murmur3 of `kind|id`, out
  of 1,024 fixed forever, and each database owns a contiguous range of slices
  in the order given. An append and its conflict check stay in one
  transaction, so nothing spans two databases.
- **Name them, and never reorder them.** The same names in the same order
  route an id the same way every time. Give snapshots the same list, and an
  id's snapshot sits beside its events.
- **A read model is one projection per database.** Each database keeps its
  own feed and order. `ShardedJournal.progress(name, database)` names each
  one's offset, and `Prune.after(offsets, journal, names)` lets a database
  prune once what reads it has caught up. Order within an id holds; there is
  no order across databases.

<!-- cluster-sharded -->
```kotlin
import dev.surefold.actor.EventCodec
import dev.surefold.actor.ShardedJournal
import dev.surefold.actor.ShardedSnapshots
import dev.surefold.actor.journal.jdbc.JdbcJournal
import dev.surefold.actor.journal.jdbc.JdbcOffsets
import dev.surefold.actor.journal.jdbc.JdbcSnapshots
import dev.surefold.actor.projection.Projection
import dev.surefold.actor.projection.runProjecting
import dev.surefold.stream.Forks
import dev.surefold.stream.Running
import dev.surefold.stream.start
import javax.sql.DataSource

object Paid : EventCodec<Long> {
    override fun encode(event: Long): ByteArray = event.toString().toByteArray()

    override fun decode(bytes: ByteArray): Long = String(bytes).toLong()
}

/** The same names, in the same order, for events and for snapshots. */
fun journal(a: DataSource, b: DataSource) = ShardedJournal(listOf("db-a" to JdbcJournal(a), "db-b" to JdbcJournal(b)))

fun snapshots(a: DataSource, b: DataSource) =
    ShardedSnapshots(listOf("db-a" to JdbcSnapshots(a), "db-b" to JdbcSnapshots(b)))

/** The ledger as one projection per database, each saving its own offset. */
fun ledgers(journal: ShardedJournal, offsets: JdbcOffsets): List<Running<Nothing, Long>> =
    journal.feeds.map { (database, feed) ->
        Projection.follow(feed, "account", Paid, offsets, ShardedJournal.progress("ledger", database))
            .runProjecting()
            .start(Forks())
    }
```

## Commands that must arrive

A tell across nodes is at most once, and a message already in an entity's
mailbox when its shard moves is dropped. For a command that must not be lost,
such as a payment, send it reliably:

- **The command carries its delivery.** It implements `Delivered`, and its
  codec writes the `Delivery` with `out.delivery(…)` and reads it back with
  `input.delivery()`. The producer's address crosses inside it, so the
  confirmation finds its way back.
- **The entity confirms.** Wrap the behaviour in `delivered(…)`: each
  delivered command is confirmed once its turn has run, after its events are
  written. A persistent entity remembers the last sequence number it handled
  from each producer, in the same append as the events, and drops a
  duplicate, confirming it again.
- **The producer resends.** `sharded.reliable(id)` numbers each command per
  entity, keeps it until it is confirmed, and sends it again every
  `resendAfter`. One command per entity is in flight at a time, so they arrive
  in order however many copies are lost. `send` waits for room when `keep` are
  unconfirmed, and answers `Full` after `within`.

<!-- cluster-reliable -->
```kotlin
import dev.surefold.actor.Delivered
import dev.surefold.actor.Delivery
import dev.surefold.actor.EventCodec
import dev.surefold.actor.NotSent
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.delivered
import dev.surefold.actor.persistent
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.remote.delivery
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.sharding
import dev.surefold.fail.Either
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A payment into a wallet, sent reliably. */
data class Pay(val pence: Long, override val delivery: Delivery) : Delivered

object PayCodec : MessageCodec<Pay> {
    override fun write(message: Pay, out: WireOut) {
        out.long(message.pence)
        out.delivery(message.delivery)
    }

    override fun read(input: WireIn): Pay = Pay(input.long(), input.delivery())
}

object Paid : EventCodec<Long> {
    override fun encode(event: Long): ByteArray = event.toString().toByteArray()

    override fun decode(bytes: ByteArray): Long = String(bytes).toLong()
}

/** A wallet that applies each payment once, however many times it is sent. */
fun wallet(id: String) = delivered(
    persistent<Pay, Long, Long>(
        id = PersistenceId("wallet", id),
        empty = 0,
        codec = Paid,
        command = { _, _, pay -> persist(pay.pence) },
        event = { balance, paid -> balance + paid },
    ),
)

/** Pays into a wallet from this node; the payment arrives however the wallet moves meanwhile. */
fun Cluster.payments(): (wallet: String, pence: Long) -> Either<NotSent, Unit> {
    val wallets = sharding("wallet", PayCodec, passivateAfter = 5.minutes) { id -> wallet(id) }
    val checkout = wallets.reliable("checkout", resendAfter = 2.seconds, keep = 10_000)
    return { wallet, pence -> checkout.send(wallet) { delivery -> Pay(pence, delivery) } }
}
```

What the producer has not had confirmed lives in its memory: a producer that
crashes loses it, and one that stops properly waits for it first (the next
section). Where a crash must lose nothing, pass `durable = true`: the producer
keeps each command in the scope's journal before `send` returns, and one started
again under the same id sends what is still unconfirmed. Its commands implement
`Delivered.redeliver`, usually as `copy(delivery = delivery)`. The journal
keeps it under the node's life as well as the id, its name and `Cluster.uid`,
so each restart of a node has an outbox of its own. A singleton,
`surefold-producers`, lists every such producer. When a life leaves the cluster,
the singleton starts that life's producers on its own node, and retires each
once everything it kept is confirmed. A node that never comes back loses
nothing it accepted.

A durable `send` can fail for a second reason: the journal throws, because the
database is down or a connection was lost. It then answers `Unwritten` with
what the journal threw, and nothing was kept. The producer restarts itself,
100 ms after the throw and doubling to 5 s, and until its journal can be read
again every `send` answers `Unwritten` at once rather than waiting out
`within`. Neither answer is retried for the caller, so a service tells "slow
down" from "unavailable" by matching on `NotSent`:

<!-- cluster-reliable-answers -->
```kotlin
import dev.surefold.actor.Full
import dev.surefold.actor.NotSent
import dev.surefold.actor.Unwritten
import dev.surefold.fail.Either

/** The HTTP status a checkout answers for a payment it sent, or did not. */
fun status(sent: Either<NotSent, Unit>): Int = when (sent) {
    is Either.Right -> 202
    is Either.Left -> when (val why = sent.value) {
        Full -> 429 // too many unconfirmed: slow down
        is Unwritten -> 503 // the journal refused it: why.cause says why
    }
}
```

### A command answered once per key

Reliable delivery keeps a command from being lost, and a persistent entity drops a resend of one it already
handled. It does not answer the resend, and nothing can ask it "did you already apply this?". A `keyed` entity
does both (spec 0300). Each command carries a key, such as `transfer-7/debit`. Its events and its key's answer are
written in one append. A repeat of the key gets the same answer, a refusal included, and writes nothing.
`Keyed.Applied(key)` answers what a key got, or `Unknown`, without deciding anything.

<!-- cluster-keyed -->
```kotlin
import dev.surefold.actor.EventCodec
import dev.surefold.actor.KeyAnswer
import dev.surefold.actor.Keyed
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.StateCodec
import dev.surefold.actor.ask
import dev.surefold.actor.every
import dev.surefold.actor.keyed
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.remote.codec
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.Sharded
import dev.surefold.cluster.sharding
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Pence into or out of a wallet, and the one reason a wallet says no. */
object Overdrawn

object PenceEvents : EventCodec<Long> {
    override fun encode(event: Long): ByteArray = event.toString().toByteArray()

    override fun decode(bytes: ByteArray): Long = String(bytes).toLong()
}

object OverdrawnEvents : EventCodec<Overdrawn> {
    override fun encode(event: Overdrawn): ByteArray = ByteArray(0)

    override fun decode(bytes: ByteArray): Overdrawn = Overdrawn
}

object Balance : StateCodec<Long> {
    override fun encode(state: Long): ByteArray = state.toString().toByteArray()

    override fun decode(bytes: ByteArray): Long = String(bytes).toLong()
}

/** A wallet: a negative amount is a withdrawal, refused when it would overdraw. Keys are kept for 30 days. */
fun wallet(id: String) = keyed<Long, Long, Long, Overdrawn>(
    id = PersistenceId("wallet", id),
    empty = 0,
    codec = PenceEvents,
    refusals = OverdrawnEvents,
    decide = { balance, pence -> if (balance + pence < 0) refuse(Overdrawn) else accept(pence) },
    event = { balance, pence -> balance + pence },
    remember = 30.days,
    snapshots = every(100, Balance),
)

object OverdrawnWire : MessageCodec<Overdrawn> {
    override fun write(message: Overdrawn, out: WireOut) = Unit

    override fun read(input: WireIn): Overdrawn = Overdrawn
}

fun Cluster.wallets(): Sharded<Keyed<Long, Overdrawn>> =
    sharding("wallet", Keyed.codec(Codecs.long, OverdrawnWire), passivateAfter = 5.minutes) { id -> wallet(id) }

/** Withdraws under [key]: asking again with the same key, after a lost answer, never withdraws twice. */
fun Sharded<Keyed<Long, Overdrawn>>.withdraw(wallet: String, key: String, pence: Long) =
    entity(wallet).ask<Keyed<Long, Overdrawn>, KeyAnswer<Overdrawn>>(5.seconds) { Keyed.Apply(key, -pence, it) }
```

- **A key is remembered for `remember`,** 30 days by default. Each answer written drops the keys older than that,
  the same way on every replay, so an entity's state does not grow forever. A repeat that arrives later is decided
  again, so whatever resends a key must give up on it before then.
- **A snapshot keeps the keys** with the state, so a start from it answers them as before.
- **Across nodes,** `Keyed.codec(commands, refusals)` carries the key, the command and the reply the answer goes back
  to.

## A saga that outlives its node

A transfer between two wallets is two commands, and a node can die between them. `surefold-cluster-saga` (spec
0299) writes the transfer as plain blocking code, journals each step as it begins and ends, and runs each saga as an
entity of its own, sharded by its id. After a crash the body runs again from the top on whichever node owns it now:
a step already ended answers what it answered then, and calls nothing.

<!-- cluster-saga -->
```kotlin
import dev.surefold.actor.EventCodec
import dev.surefold.actor.Keyed
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.StateCodec
import dev.surefold.actor.keyed
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.remote.codec
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.Sharded
import dev.surefold.cluster.saga.Durable
import dev.surefold.cluster.saga.Sagas
import dev.surefold.cluster.saga.durableSaga
import dev.surefold.cluster.saga.entityStep
import dev.surefold.cluster.saga.on
import dev.surefold.cluster.sharding
import dev.surefold.fail.fold
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

object Overdrawn

object PenceEvents : EventCodec<Long> {
    override fun encode(event: Long): ByteArray = event.toString().toByteArray()

    override fun decode(bytes: ByteArray): Long = String(bytes).toLong()
}

object OverdrawnEvents : EventCodec<Overdrawn> {
    override fun encode(event: Overdrawn): ByteArray = ByteArray(0)

    override fun decode(bytes: ByteArray): Overdrawn = Overdrawn
}

object OverdrawnWire : MessageCodec<Overdrawn> {
    override fun write(message: Overdrawn, out: WireOut) = Unit

    override fun read(input: WireIn): Overdrawn = Overdrawn
}

/** The wallet of the section above: pence in or out, refused when it would overdraw. */
fun Cluster.wallets(): Sharded<Keyed<Long, Overdrawn>> =
    sharding("wallet", Keyed.codec(Codecs.long, OverdrawnWire), passivateAfter = 5.minutes) { id ->
        keyed<Long, Long, Long, Overdrawn>(
            id = PersistenceId("wallet", id),
            empty = 0,
            codec = PenceEvents,
            refusals = OverdrawnEvents,
            decide = { balance, pence -> if (balance + pence < 0) refuse(Overdrawn) else accept(pence) },
            event = { balance, pence -> balance + pence },
        )
    }

data class Move(val from: String, val to: String, val pence: Long)

object Moves : StateCodec<Move> {
    override fun encode(state: Move): ByteArray = "${state.from}|${state.to}|${state.pence}".toByteArray()

    override fun decode(bytes: ByteArray): Move =
        String(bytes).split("|").let { (from, to, pence) -> Move(from, to, pence.toLong()) }
}

object Words : StateCodec<String> {
    override fun encode(state: String): ByteArray = state.toByteArray()

    override fun decode(bytes: ByteArray): String = String(bytes)
}

/** Every node calls this on its own scope, with the same saga. */
fun Cluster.transfers(wallets: Sharded<Keyed<Long, Overdrawn>>): Sagas<Move, String, String> =
    durableSaga("transfer", Moves, Words, Words) { move ->
        entityStep("debit", wallets, move.from, refused = { "overdrawn" }, undo = { move.pence }) { -move.pence }
        entityStep("credit", wallets, move.to, refused = { "overdrawn" }) { move.pence }
        "moved ${move.pence} from ${move.from} to ${move.to}"
    }.on(this)

/** Starts the transfer [id], or finds it started, and waits up to ten seconds for how it ends. */
fun Sagas<Move, String, String>.pay(id: String, move: Move): Durable.Outcome<String, String> =
    start(id, move).fold({ Durable.Outcome.Running }) { await(id, within = 10.seconds) }
```

- **`entityStep`** has a keyed entity apply a command under the step's key, `"$sagaId/$stepName"`. A refusal fails the
  saga with `refused` of its reason, and the steps before it are undone, newest first, under `…/undo`. An answer
  lost on the way back is asked for with `Keyed.Applied`, so the command is sent again only if the entity never
  had it. A plain `step(name, codec, action, check, undo)` does the same for anything else: `check` says whether a
  step that began before a crash happened.
- **`start` is idempotent on the id**, and answers once the saga's start is journalled. **`await`** can be called
  from any node, and answers `Done`, `Refused`, `Running` (not ended within the wait), `Stuck` or `Forgotten` (the
  journal holds nothing of it).
- **Nothing unfinished is left asleep.** The `surefold-sagas` singleton lists each saga before it starts and wakes
  every one not yet ended when it starts and when a member is removed, so a saga whose node died between two steps
  ends elsewhere without anyone asking.
- **`Stuck`** is an undo that kept failing, or a step that began and has nothing to settle it. It undoes nothing
  further until `resume`. A saga stopped longer than an entity remembers a key (`remember`, 30 days by default)
  refuses to resume as `Outlived`, since a forgotten key would answer `Unknown` for a command that was applied.
- **`now()` and `uuid()`** in a body are journalled, so a replay sees the same time and the same id. `on` reads the
  clock and metrics where it is called, so call it on the node's scope, as `cluster` and `sharding` are.

## Stopping, watching and telling everyone

### Stopping

A node whose scope closes leaves its cluster first. Its close hooks run before
any actor stops, so the node is still whole while it leaves. First a producer
on it waits for what it keeps to be confirmed: up to `drainWithin` for
`scope.producer`, and up to the cluster's `leaveWithin` for `reliable`. Then
the node leaves, waiting up to `leaveWithin` (30 seconds by default) to be
removed. Its shards move to their next owners while it is `Leaving`. The
others see it leave and remove it, rather than finding it unreachable and
downing it `stableAfter` later.
`cluster.stop(within)` does the same before the scope closes, for a service
that has something of its own to do after. A `leaveWithin` of zero leaves
nothing, and the node goes as a crashed one does.

In a `surefold-app` application, start the cluster in the actors' scope with
`Actors.within`, so that releasing the application on SIGTERM leaves the
cluster before its actors stop. The same node answers `/ready` through a probe
on `ready()`.

<!-- cluster-operating -->
```kotlin
import dev.surefold.actor.remote.node
import dev.surefold.app.Module
import dev.surefold.app.actor.Actors
import dev.surefold.app.actor.actors
import dev.surefold.app.probe
import dev.surefold.app.single
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.Discovery
import dev.surefold.cluster.cluster
import kotlin.time.Duration.Companion.seconds

/** The cluster as a node of the application: in the actors' scope, left on release, and ready once it is in. */
val clusterNode: Module = actors() +
    single { actors: Actors ->
        actors.within {
            val node = node("shop-1", 25520, host = "0.0.0.0")
            cluster(node, Discovery.srv("_surefold._tcp.shop.internal"), leaveWithin = 20.seconds)
        }
    }.probe("cluster", timeout = 2.seconds) { cluster: Cluster -> cluster.ready() }
```

Give the platform's grace period room for both deadlines. A pod given 30
seconds to stop, with a producer that may drain for 20 and a cluster that may
leave for 20, is killed partway through leaving, and the others then treat it
as crashed.

### Watching

Every scope records through surefold's `metrics`, so a service with
surefold-integrations' `surefold-micrometer` installed exports these with nothing more to write. Each
carries a `node` tag naming the node it was recorded on.

| Metric | Kind | What it tells an operator |
| --- | --- | --- |
| `surefold.actor.dead_letters{reason}` | counter | Messages to actors that had stopped, left unhandled, or for a node that could not be reached |
| `surefold.actor.restarts` | counter | Actors supervision restarted after a failure |
| `surefold.remote.frames{peer, direction}` | counter | Traffic to and from each peer |
| `surefold.remote.dropped{peer}` | counter | Frames for a peer that was down or whose queue was full |
| `surefold.remote.connected{peer}` | gauge | Whether this node's connection to a peer is up |
| `surefold.cluster.members{status}` | gauge | How many members this node sees in each status |
| `surefold.cluster.unreachable` | gauge | Members some member cannot reach: nothing moves on while this is above zero |
| `surefold.cluster.leader` | gauge | Whether this node leads |
| `surefold.cluster.downed` | counter | Members downed: a crash, or a partition decided |
| `surefold.sharding.shards{kind}` | gauge | Shards this node may run now |
| `surefold.sharding.entities{kind}` | gauge | Entities running here |
| `surefold.sharding.buffered{kind}` | gauge | Messages kept for a shard without an owner yet |
| `surefold.delivery.unconfirmed{producer}` | gauge | Commands a producer keeps and no entity has confirmed |
| `surefold.delivery.resent{producer}` | counter | Commands sent again: a move, a crash, or a lost confirmation |
| `surefold.delivery.full{producer}` | counter | Sends that gave up waiting for room |
| `surefold.delivery.unwritten{producer}` | counter | Durable sends refused because the journal threw |
| `surefold.topic.published{topic}` | counter | Messages published on this node |
| `surefold.topic.delivered{topic}` | counter | Messages told to this node's subscribers |
| `surefold.topic.subscribers{topic}` | gauge | Subscribers on this node |

Nothing counts each tell or times each turn: a metric on the hottest path
would cost what the actors are built to avoid.

### Telling everyone

An event several nodes must hear, such as a price every node caches, is a
topic. A publish on
any member reaches every subscriber on every `Up` member; a member still joining,
or already leaving, misses what is published meanwhile. Delivery is at most once
to each subscriber, and one member's publishes arrive in the order it made
them. A subscriber that stops is dropped. Every node calls `topic` with the
same name and codec.

<!-- cluster-topic -->
```kotlin
import dev.surefold.Scope
import dev.surefold.actor.Topic
import dev.surefold.actor.become
import dev.surefold.actor.behaviour
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.spawn
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.topic

data class PriceChanged(val sku: String, val pence: Long)

object PriceCodec : MessageCodec<PriceChanged> {
    override fun write(message: PriceChanged, out: WireOut) {
        out.string(message.sku)
        out.long(message.pence)
    }

    override fun read(input: WireIn): PriceChanged = PriceChanged(input.string(), input.long())
}

/** Every node's price cache hears every change, wherever it was published. */
fun Scope<Nothing>.prices(cluster: Cluster): Topic<PriceChanged> {
    val prices = cluster.topic("prices", PriceCodec)
    val cache = behaviour<PriceChanged, Map<String, Long>>(emptyMap()) { _, known, change ->
        become(known + (change.sku to change.pence))
    }
    prices.subscribe(spawn("price-cache", cache))
    return prices
}
```

`scope.topic(name)` is the same on one node, with nothing sent to others.
