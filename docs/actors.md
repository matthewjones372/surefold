# Actors on one node

`surefold-actor` is an actor as a value: a state, and a turn for each message,
one at a time. Actors run on virtual threads inside the same structured
scope, `scope`, that the core uses for forks, so there is no actor system to
start and stop, and a turn may block.

This page covers actors in one process: failure and supervision, timers,
finding and routing, persistence, batched writes and testing. A second node, a cluster and
entities spread across it are covered in the [cluster guide](cluster.md).

Every complete example below is compiled against the library by
`ActorsGuideTest`, and the testing examples are also run, so what you read is
what builds.

```kotlin
dependencies {
    implementation("io.github.matthewjones372:surefold-actor:0.17.0")
}
```

1. [An actor](#an-actor)
2. [Failure](#failure)
3. [Time](#time)
4. [Finding and routing](#finding-and-routing)
5. [Remembering](#remembering)
6. [Changing an event](#changing-an-event)
7. [A run at a time](#a-run-at-a-time)
8. [Testing](#testing)

## An actor

A `behaviour` is where an actor starts and what one message does to it. A turn gets
the actor's context, its state and one message, and answers what happens next:

- `stay()` keeps the state;
- `become(state)` replaces it;
- `stop()` ends the actor;
- `unhandled()` keeps the state and sends the message where dead letters go.

Nothing runs until the behaviour is spawned. An actor lives in a `scope`, the
structured scope `surefold` already has, and cannot outlive it: when the scope's
block ends, every actor in it is stopped, and the scope returns only once they
have. On virtual threads a turn still running at that point is interrupted; on
another executor it is waited for.

An idle actor holds no thread. An actor with messages waiting is run by the
scope's runners, virtual threads shared by its actors, one per carrier thread
to start with and more while turns are blocked. So a turn may block on JDBC or
a client with no async API without starving the other actors.

`tell` sends and returns. A mailbox holds 1,024 messages by default
(`capacity` on `spawn`): a sender outside any actor waits while it is full,
and a turn that tells a full mailbox fails. `ask` sends a message carrying a
`Reply` and blocks the caller for up to a timeout, answering with an
`Either`: an actor that stopped, took too long or could not be reached is an
`AskFailure` value rather than an exception.

<!-- actors-actor -->
```kotlin
import dev.surefold.scope
import io.github.matthewjones372.surefold.actor.Reply
import io.github.matthewjones372.surefold.actor.ask
import io.github.matthewjones372.surefold.actor.become
import io.github.matthewjones372.surefold.actor.behaviour
import io.github.matthewjones372.surefold.actor.spawn
import io.github.matthewjones372.surefold.actor.stay
import io.github.matthewjones372.surefold.actor.stop
import io.github.matthewjones372.surefold.actor.unhandled
import kotlin.time.Duration.Companion.seconds

sealed interface Till

data class Ring(val pence: Int) : Till

data class Total(val reply: Reply<Int>) : Till

/** Takes nothing back: the till answers `unhandled()`, and a refund is a dead letter. */
data class Refund(val pence: Int) : Till

data object Close : Till

fun till() = behaviour<Till, Int>(0) { _, total, message ->
    when (message) {
        is Ring -> become(total + message.pence)
        is Total -> stay().also { message.reply(total) }
        is Refund -> unhandled()
        Close -> stop()
    }
}

fun main() {
    val total = scope {
        val till = spawn("till", till())
        till.tell(Ring(250))
        till.tell(Ring(120))
        till.ask(1.seconds) { Total(it) }
    }
    println(total) // Right(value=370)
}
```

A turn handles one message, and turns run one at a time, so the state needs no
lock. Read it and answer with a new one; never share a mutable one with
anything outside the actor.

To tell an actor from inside `transact { }`, use `tellAfterCommit(ref, message)`
rather than `tell`: the transaction can run more than once, and a `tell`
written in it goes out each time. See
[effects once the step commits](core.md#effects-once-the-step-commits).

## Failure

A turn fails in one of two ways:

- **It fails** with a failure it declared. A behaviour names its failure type
  as its third type parameter, `behaviour<M, S, E>`, and its turn fails with
  `fail`. A behaviour that never fails is a
  `Behaviour<M, S, Nothing>`.
- **It throws**, for what nobody declared: a bug, or a dependency that broke.

Either way, the actor's `restart` schedule decides. While the schedule
continues, the actor starts again from its initial state after the schedule's
delay, with the messages still in its mailbox. Once the schedule is done, or if
there is none, the actor stops. The schedule is `surefold`'s `Schedule`, the same
one a retry takes, and it sees each failure as a `Failure.Declared` or a
`Failure.Thrown`.

Signals are what happens to an actor rather than what is sent to it:
`Signal.Stopping` as it ends, and `Signal.Terminated` when an actor it watches
has stopped. `onSignal` takes them beside the message turns, and `onStart` runs before
the first message and again after each restart.

An actor spawns children with `ctx.spawn`. A child is stopped before its parent
stops or restarts, so a parent that restarts has no children until a turn
spawns them again. `ctx.watch` asks to hear when another actor stops, child or
not; `watch` on the scope gives the same as a `Deferred` to await.

<!-- actors-failure -->
```kotlin
import dev.surefold.Schedule
import dev.surefold.Scope
import dev.surefold.logInfo
import io.github.matthewjones372.surefold.actor.ActorRef
import io.github.matthewjones372.surefold.actor.Failure
import io.github.matthewjones372.surefold.actor.Reply
import io.github.matthewjones372.surefold.actor.Signal
import io.github.matthewjones372.surefold.actor.become
import io.github.matthewjones372.surefold.actor.behaviour
import io.github.matthewjones372.surefold.actor.onSignal
import io.github.matthewjones372.surefold.actor.onStart
import io.github.matthewjones372.surefold.actor.spawn
import io.github.matthewjones372.surefold.actor.stay
import io.github.matthewjones372.surefold.actor.watch
import kotlin.time.Duration.Companion.milliseconds

sealed interface Feed

data class Price(val sku: String, val pence: Int) : Feed

data class Quote(val sku: String, val reply: Reply<Int>) : Feed

/** What the price feed declares it can fail with. */
data class BadPrice(val sku: String, val pence: Int)

fun prices() = behaviour<Feed, Map<String, Int>, BadPrice>(emptyMap()) { _, prices, message ->
    when (message) {
        is Price -> {
            if (message.pence < 0) fail(BadPrice(message.sku, message.pence))
            become(prices + (message.sku to message.pence))
        }

        // An unknown sku throws: nobody declared it, and the schedule sees it as Failure.Thrown.
        is Quote -> stay().also { message.reply(prices.getValue(message.sku)) }
    }
}.onStart { ctx -> logInfo("${ctx.self.address.path} starts with no prices") }

/** Up to five restarts, each waiting twice as long as the last; then the feed stops. */
val backingOff: Schedule<Failure<BadPrice>, *> =
    Schedule.exponential<Failure<BadPrice>>(100.milliseconds) zipLeft Schedule.recurs(5)

/** One feed per region, each a child: a feed that stops is forgotten, and started again when next asked for. */
fun feeds() = behaviour<Pair<String, Feed>, Map<String, ActorRef<Feed>>>(emptyMap()) { ctx, feeds, (region, feed) ->
    val running = feeds[region] ?: ctx.spawn("prices-$region", prices(), restart = backingOff).also(ctx::watch)
    running.tell(feed)
    if (region in feeds) stay() else become(feeds + (region to running))
}.onSignal { _, feeds, signal ->
    if (signal is Signal.Terminated) become(feeds.filterValues { it != signal.ref }) else stay()
}

/** Watching from outside an actor: the scope's own watch, awaited. */
fun Scope<Nothing>.untilStopped(feed: ActorRef<Feed>): Signal.Terminated = watch(feed).await()
```

A failure that belongs to an `attempt { }` inside the turn is that `attempt`'s,
and never mistaken for the actor's own, so a turn can use `attempt` as it would
anywhere else.

## Time

An actor's timers send it messages later, under a key:
`ctx.timers.after` once, `ctx.timers.every` repeatedly, and `ctx.timers.cancel`
to take one back. Starting a key that is running replaces it, and a message
from a cancelled timer never arrives, even one already in the mailbox. A
restart cancels them all, which is why timers are usually started in `onStart`.

A receive timeout, `ctx.receiveTimeout`, sends a message once nothing has
arrived for a while. A state's own timers, `ctx.become(state) { after(…) }`,
end when the actor becomes a state of another class, so a timeout that belongs
to "waiting for payment" cannot fire once payment has arrived.

Every wait runs on the scope's clock, which is `surefold`'s `clock`. Bind a
`TestClock` around the scope and move it with `adjust`, and an hour's timers
fire in no time at all.

<!-- actors-time -->
```kotlin
import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.scope
import io.github.matthewjones372.surefold.actor.ActorRef
import io.github.matthewjones372.surefold.actor.awaitIdle
import io.github.matthewjones372.surefold.actor.become
import io.github.matthewjones372.surefold.actor.behaviour
import io.github.matthewjones372.surefold.actor.onStart
import io.github.matthewjones372.surefold.actor.spawn
import io.github.matthewjones372.surefold.actor.stay
import io.github.matthewjones372.surefold.actor.stop
import io.github.matthewjones372.surefold.actor.unhandled
import io.github.matthewjones372.surefold.actor.watch
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

sealed interface Basket

data class Add(val sku: String) : Basket

data object Checkout : Basket

data object Paid : Basket

data object Remind : Basket

data object Abandon : Basket

sealed interface Stage

data class Shopping(val items: List<String>) : Stage

data class Paying(val items: List<String>) : Stage

fun basket(shopper: ActorRef<String>) = behaviour<Basket, Stage>(Shopping(emptyList())) { ctx, stage, message ->
    when {
        message == Remind -> stay().also { shopper.tell("your basket is waiting") }
        message == Abandon -> stop()
        stage is Shopping && message is Add -> become(Shopping(stage.items + message.sku))
        // Payment has ten minutes. The timer is the Paying state's, so it cannot fire once the basket moves on.
        stage is Shopping && message == Checkout -> ctx.become(Paying(stage.items)) { after(10.minutes, Abandon) }
        stage is Paying && message == Paid -> stop()
        else -> unhandled()
    }
}.onStart { ctx ->
    ctx.timers.every("remind", 1.hours, Remind)
    ctx.receiveTimeout(2.hours, Abandon)
}

fun main() {
    val moving = TestClock()
    clock.locally(moving) {
        scope {
            val shopper = spawn("shopper", behaviour<String, Unit>(Unit) { _, _, note -> stay().also { println(note) } })
            val basket = spawn("basket", basket(shopper))
            basket.tell(Add("sku-1"))
            basket.tell(Checkout)
            awaitIdle()

            // Ten minutes pass with no payment, in no time: the basket is abandoned.
            moving.adjust(10.minutes)
            watch(basket).await()
        }
    }
}
```

## Finding and routing

An actor that should be found registers under a `ServiceKey`, a protocol by
name.
`ctx.register` lists it until it stops or restarts; `find` on the scope answers
the refs listed now; and `ctx.subscribe` tells an actor the listing now and
again each time it changes, as a message of its own protocol. A registration
is lost on a restart, like a timer, so it belongs in `onStart`.

Two routers spread messages over several actors:

- **`group`** makes one ref from refs already running. A tell picks one on the
  caller's thread, with no actor between.
- **`pool`** is an actor that spawns its routees as its children and passes each
  message to one of them. A routee that fails restarts on its own, and one
  whose mailbox is full is passed over.

Both take a `Route`: `roundRobin()` by default, or `hashing { … }`, so one key
always reaches one routee.

`entities` runs one actor per id, as children of one manager: started on the
id's first message, and stopped once it has had nothing for a while. A ref from
`entity(id)` stays good while the entity comes and goes. It is sharding on one
node, and the [cluster guide](cluster.md#entities) spreads the same thing over
many.

<!-- actors-routing -->
```kotlin
import dev.surefold.Scope
import io.github.matthewjones372.surefold.actor.ActorRef
import io.github.matthewjones372.surefold.actor.ServiceKey
import io.github.matthewjones372.surefold.actor.awaitIdle
import io.github.matthewjones372.surefold.actor.become
import io.github.matthewjones372.surefold.actor.behaviour
import io.github.matthewjones372.surefold.actor.entities
import io.github.matthewjones372.surefold.actor.entity
import io.github.matthewjones372.surefold.actor.find
import io.github.matthewjones372.surefold.actor.group
import io.github.matthewjones372.surefold.actor.hashing
import io.github.matthewjones372.surefold.actor.onStart
import io.github.matthewjones372.surefold.actor.pool
import io.github.matthewjones372.surefold.actor.spawn
import io.github.matthewjones372.surefold.actor.stay
import kotlin.time.Duration.Companion.minutes

data class Job(val owner: String, val document: String)

val Printers = ServiceKey<Job>("printers")

fun printer() = behaviour<Job, Int>(0) { _, printed, _ -> become(printed + 1) }
    .onStart { ctx -> ctx.register(Printers) }

sealed interface Spool

data class Print(val job: Job) : Spool

data class Listing(val printers: Set<ActorRef<Job>>) : Spool

/** Follows the printers as they come and go, and hands each job to one of those listed now. */
fun spooler() = behaviour<Spool, Set<ActorRef<Job>>>(emptySet()) { _, printers, message ->
    when (message) {
        is Listing -> become(message.printers)
        is Print -> stay().also { printers.firstOrNull()?.tell(message.job) }
    }
}.onStart { ctx -> ctx.subscribe(Printers, ::Listing) }

fun Scope<Nothing>.office() {
    repeat(3) { spawn("printer-$it", printer()) }
    awaitIdle()

    // Refs already running, as one: each tell goes to the next printer in turn.
    val printers = group(find(Printers).toList())
    printers.tell(Job("ann", "report"))

    // Four printers of its own, and one owner's jobs always to the same one.
    val spool = spawn("pool", pool(4, hashing<Job> { it.owner }) { printer() })
    spool.tell(Job("bo", "invoice"))

    spawn("spooler", spooler()).tell(Print(Job("cy", "memo")))

    // One printer per desk, started on first use and stopped after half an hour idle.
    val desks = spawn("desks", entities(passivateAfter = 30.minutes) { _ -> printer() })
    desks.entity("desk-7").tell(Job("di", "letter"))
}
```

## Remembering

A `persistent` actor is remembered by its events. A command answers
an effect instead of a next state:

- `persist(events)` writes them to the journal, then applies them with `event`;
- `none()` writes nothing;
- either can be followed by `then { state -> … }`, which runs once the events
  are written and applied, which is where a reply goes.

On start, and after every restart, the actor replays its events before its
first command. Its state is a `Remembered`: the value the events built and the
number of the last one. An append that finds another writer has got there first
fails as a `JournalConflict`, so supervision decides.

With `persistent(…, batch = n)`, up to `n` commands already waiting in the
mailbox are handled together and their events written in one append, which
raises throughput for a busy entity; a conflict fails the whole batch.

With `persistent(…, start = Start.assumingNew)`, an actor's first start reads
nothing and begins at `empty`, which saves a read for an entity created just
before its first command, such as a saga with a fresh id. The first command
checks the guess: an append that expects no events before it proves the
entity new. If it conflicts, or the command persists nothing, the actor
restarts without asking supervision, recovers, runs its start hooks again and
handles the same command against what it recovered. Its `then` never ran for
the guess. With `batch = n`, the first run of commands is checked the same way:
one append for all of them proves the entity new, and a run that writes nothing
or conflicts is handed back whole, in order, once the actor has recovered.

The journal and the snapshot store are the scope's. `InMemoryJournal` and
`InMemorySnapshots` keep everything in the process, for tests and for trying
things out. With `snapshots = every(n, codec)`, the actor saves its state every
`n` events and a start replays only what came after the newest save.
A journal that outlives the node, on Postgres, and read models that follow it
are the [cluster guide](cluster.md#state-that-survives)'s.

<!-- actors-persistent -->
```kotlin
import dev.surefold.scope
import io.github.matthewjones372.surefold.actor.EventCodec
import io.github.matthewjones372.surefold.actor.InMemoryJournal
import io.github.matthewjones372.surefold.actor.InMemorySnapshots
import io.github.matthewjones372.surefold.actor.PersistenceId
import io.github.matthewjones372.surefold.actor.Reply
import io.github.matthewjones372.surefold.actor.StateCodec
import io.github.matthewjones372.surefold.actor.ask
import io.github.matthewjones372.surefold.actor.every
import io.github.matthewjones372.surefold.actor.journal
import io.github.matthewjones372.surefold.actor.persistent
import io.github.matthewjones372.surefold.actor.snapshots
import io.github.matthewjones372.surefold.actor.spawn
import io.github.matthewjones372.surefold.actor.stop
import java.nio.ByteBuffer
import kotlin.time.Duration.Companion.seconds

sealed interface Account

data class Deposit(val pence: Long) : Account

data class Withdraw(val pence: Long, val reply: Reply<Boolean>) : Account

data class Balance(val reply: Reply<Long>) : Account

/** What happened to an account: a change to its balance, in pence. */
data class Moved(val pence: Long)

object MovedCodec : EventCodec<Moved> {
    override fun encode(event: Moved): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(event.pence).array()

    override fun decode(bytes: ByteArray): Moved = Moved(ByteBuffer.wrap(bytes).long)
}

object BalanceCodec : StateCodec<Long> {
    override fun encode(state: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(state).array()

    override fun decode(bytes: ByteArray): Long = ByteBuffer.wrap(bytes).long
}

fun account(id: String) = persistent<Account, Moved, Long>(
    id = PersistenceId("account", id),
    empty = 0,
    codec = MovedCodec,
    command = { _, balance, command ->
        when (command) {
            is Deposit -> persist(Moved(command.pence))
            is Withdraw ->
                if (command.pence > balance) {
                    none().then { command.reply(false) }
                } else {
                    persist(Moved(-command.pence)).then { command.reply(true) }
                }
            is Balance -> none().then { command.reply(balance) }
        }
    },
    event = { balance, moved -> balance + moved.pence },
    snapshots = every(100, BalanceCodec),
)

fun main() {
    val balance = scope {
        journal(InMemoryJournal())
        snapshots(InMemorySnapshots())
        val first = spawn("a-1", account("a-1"))
        first.tell(Deposit(500))
        first.ask<Account, Boolean>(1.seconds) { Withdraw(200, it) }
        stop(first).await()

        // The same id, started again: it replays its events and carries on where the first left off.
        val again = spawn("a-1-again", account("a-1"))
        again.ask(1.seconds) { Balance(it) }
    }
    println(balance) // Right(value=300)
}
```

## Changing an event

Events outlive the code that wrote them. When an event's class changes, the
journal still holds every event written in the old shape, and a replay has to
read them.

`versioned` wraps the codec a service already has. It writes each event as
the current version, behind a short mark, and reads an older one through an
upgrade from each version on the way. An upgrade turns the bytes of version
n into the bytes of version n+1, one event or several, so a new version adds
one upgrade and the older ones stay as they are. Bytes with no mark were
written before the codec was versioned, and are version 1. A chain with a gap
is refused when the codec is built, not when an old event is replayed.

`versionedState` does the same for a snapshot's state, one state for one.

<!-- actors-versioned -->
```kotlin
import io.github.matthewjones372.surefold.actor.EventCodec
import io.github.matthewjones372.surefold.actor.PersistenceId
import io.github.matthewjones372.surefold.actor.Upgrade
import io.github.matthewjones372.surefold.actor.persistent
import io.github.matthewjones372.surefold.actor.versioned

/** Version 1: an order paid in one go. */
data class PaidV1(val pence: Long)

/** Version 2, today's: a payment of one part of an order, so one old payment is one part. */
data class PartPaid(val part: Int, val pence: Long)

object PaidV1Codec : EventCodec<PaidV1> {
    override fun encode(event: PaidV1): ByteArray = "${event.pence}".toByteArray()

    override fun decode(bytes: ByteArray): PaidV1 = PaidV1(String(bytes).toLong())
}

object PartPaidCodec : EventCodec<PartPaid> {
    override fun encode(event: PartPaid): ByteArray = "${event.part}:${event.pence}".toByteArray()

    override fun decode(bytes: ByteArray): PartPaid =
        String(bytes).split(":").let { (part, pence) -> PartPaid(part.toInt(), pence.toLong()) }
}

val payments = versioned(
    current = 2,
    codec = PartPaidCodec,
    upgrades = mapOf(
        1 to Upgrade { bytes -> listOf(PartPaidCodec.encode(PartPaid(1, PaidV1Codec.decode(bytes).pence))) },
    ),
)

fun order(id: String) = persistent<Long, PartPaid, Long>(
    id = PersistenceId("order", id),
    empty = 0,
    codec = payments,
    command = { _, _, pence -> persist(PartPaid(1, pence)) },
    event = { paid, part -> paid + part.pence },
)
```

A read model following the journal sees the upgraded events through the same
codec, so it never meets an old shape.

## A run at a time

An actor that writes somewhere other than the journal can still write a batch
at once. `batching(initial, batch = n)` hands one turn the messages already
waiting, up to `n`, as one `Run`: a list, in the order they came. The turn
decides them all against its state, writes once, replies to each, and answers
one next state for the run. A lone message is never held back for a second; it
is a run of one, as are a timer's message and a stashed one put back.

Each message carries what its sender had bound, its trace and its
annotations. The turn runs inside none of it, since the run has many senders:
`within(i) { … }` runs a block inside message `i`'s own, which is where a row
that records its request's trace is written, and where its reply is sent. A
run answered `unhandled()` sends all its messages to dead letters, and a
failure fails the whole run.

<!-- actors-batching -->
```kotlin
import dev.surefold.logAnnotated
import dev.surefold.scope
import io.github.matthewjones372.surefold.actor.Reply
import io.github.matthewjones372.surefold.actor.ask
import io.github.matthewjones372.surefold.actor.batching
import io.github.matthewjones372.surefold.actor.become
import io.github.matthewjones372.surefold.actor.spawn
import kotlin.time.Duration.Companion.seconds

data class Admit(val name: String, val reply: Reply<Int>)

/** Stands in for a database: one transaction per run, one row per message. */
class Ledger {
    val rows = mutableListOf<String>()

    fun transaction(write: () -> Unit) = write()
}

fun kennel(ledger: Ledger) = batching<Admit, Int>(0, batch = 64) { _, admitted, admits ->
    ledger.transaction {
        admits.indices.forEach { i -> admits.within(i) { ledger.rows += admits[i].name } }
    }
    // Only once the write is done does anyone hear of it.
    admits.indices.forEach { i -> admits.within(i) { admits[i].reply(admitted + i + 1) } }
    become(admitted + admits.size)
}

fun main() {
    val ledger = Ledger()
    val number = scope {
        val kennel = spawn("kennel", kennel(ledger))
        logAnnotated("request_id" to "r-1") { kennel.ask(1.seconds) { Admit("rex", it) } }
    }
    println(number) // Right(value=1)
}
```

## Testing

Everything above runs without threads, driven by the test itself.
`behaviour.test()` runs one actor on the calling thread, and `testActors { }`
runs several that talk to each other. A tell from the test returns once that
message, and everything it caused, has been handled, so a test asserts straight
after it with nothing to wait for.

- **State and what went wrong.** A test actor shows its `state`, the messages it
  left `unhandled`, the `deadLetters` of the whole test, and why it stopped.
- **Restarts are recorded, not waited out.** Each restart's delay goes into
  `delays` and `restarts` counts them, so a test of a five-minute back-off takes
  no time.
- **Time moves when the test says.** `advance` moves the test's clock on and
  delivers every timer that falls due on the way, in order.
- **The journal is the test's.** `testActors` gives its actors an in-memory
  journal the test can read, and `restart()` replays it.
- **Other orders, replayed by seed.** `testActors(order = Ordering.seeded(42))`
  runs queued messages in an order picked from the seed, keeping each sender's
  messages to one receiver in order, so a race shows up under some seed and
  that seed replays it. `trace` lists every delivery for a failing test, and
  `settle()` runs messages and timers to idle, failing after 100,000 steps
  rather than hanging.
- **Lost, doubled and late messages.** `faults = Faults(drop = 0.1,
  duplicate = 0.1, delay = 0.1, maxDelay = 2.seconds)` does each to a tell
  between actors by probability from the same seed, and records it in `trace`.
  A late message arrives when the test's time reaches it. The test's own tells
  and timers are never touched.
- **Crashes.** `crash(actor)` stops an actor as its node's death would: the
  messages waiting for it, its timers, stash and children are lost, with no
  `Stopping`, and its next message starts it again from the journal and
  snapshots. `Faults(crash = 0.05)` does the same on the way to it, losing that
  message too.

The tests below are run by the build, as well as compiled, so each passes.

<!-- actors-testing -->
```kotlin
import dev.surefold.Schedule
import dev.surefold.assert.shouldBe
import dev.surefold.fail.right
import io.github.matthewjones372.surefold.actor.ActorRef
import io.github.matthewjones372.surefold.actor.Failure
import io.github.matthewjones372.surefold.actor.Reply
import io.github.matthewjones372.surefold.actor.become
import io.github.matthewjones372.surefold.actor.behaviour
import io.github.matthewjones372.surefold.actor.stay
import io.github.matthewjones372.surefold.actor.stop
import io.github.matthewjones372.surefold.actor.test
import io.github.matthewjones372.surefold.actor.testActors
import io.github.matthewjones372.surefold.actor.unhandled
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

sealed interface Till

data class Ring(val pence: Int) : Till

data class Total(val reply: Reply<Int>) : Till

data class Refund(val pence: Int) : Till

fun till() = behaviour<Till, Int, String>(0) { _, total, message ->
    when (message) {
        is Ring -> if (message.pence > 0) become(total + message.pence) else fail("rang ${message.pence}")
        is Total -> stay().also { message.reply(total) }
        is Refund -> unhandled()
    }
}

sealed interface Basket

data object Checkout : Basket

data object Paid : Basket

data object Abandon : Basket

fun basket(shopper: ActorRef<String>) = behaviour<Basket, Boolean>(false) { ctx, paying, message ->
    when {
        message == Checkout && !paying -> ctx.become(true) { after(10.minutes, Abandon) }
        message == Paid && paying -> stop()
        message == Abandon -> stop().also { shopper.tell("abandoned") }
        else -> unhandled()
    }
}

class TillTest {

    @Test
    fun `a till totals what it rang, and leaves a refund unhandled`() {
        val till = till().test()

        till.send(Ring(250))
        till.send(Refund(100))

        till.ask { Total(it) } shouldBe 250.right()
        till.unhandled shouldBe listOf(Refund(100))
    }

    @Test
    fun `a till that fails restarts empty, and each back-off is recorded rather than waited`() {
        val backingOff = Schedule.exponential<Failure<String>>(100.milliseconds) zipLeft Schedule.recurs(5)
        val till = till().test(restart = backingOff)

        till.send(Ring(250))
        till.send(Ring(-1))
        till.send(Ring(-2))

        till.state shouldBe 0
        till.delays shouldBe listOf(100.milliseconds, 200.milliseconds)
    }

    @Test
    fun `an unpaid basket is abandoned when ten minutes pass, and not before`() {
        testActors {
            val heard = mutableListOf<String>()
            val shopper = spawn("shopper", behaviour<String, Unit>(Unit) { _, _, note -> stay().also { heard += note } })
            val basket = spawn("basket", basket(shopper))

            basket.send(Checkout)
            advance(9.minutes)
            basket.stopped shouldBe false

            advance(1.minutes)
            basket.stopped shouldBe true
            heard shouldBe listOf("abandoned")
        }
    }
}
```

A test that needs real threads, a blocking client in a turn say, runs the
same behaviours in a `scope` instead, with a `TestClock` for its time.

Where a second node comes in, the [cluster guide](cluster.md) takes over.
