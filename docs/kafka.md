# Kafka

**A Kafka record is committed only after the work on it is done.** A
subscription is a `Stream` of `Committed` elements, each carrying its record's
offset, and the only way to run one is `runCommitting`. Forgetting to commit,
or committing before the work, does not compile.

Everything below is in `dev.surefold.kafka`. A Kafka
pipeline is an ordinary [surefold-stream](stream.md) `Stream`, so the operators,
backends and test support described there apply here too.

## Delivery at a glance

| Pipeline | Offsets committed | Guarantee |
|---|---|---|
| `Kafka.consume(…)…runCommitting()` | once the record's element reaches the end of the stream | at least once: a crash or restart before the commit handles the record again |
| `…publishRecord(producer) { … }.runCommitting()` | once the output is acknowledged by the broker, in order | at least once: an output can be written twice |
| `…publishBatch(producer) { … }.absolve().runCommitting()` | once the output is acknowledged, up to the first that failed | at least once: the failed record and those after it are sent again |
| `…transacted(producer) { … }` | in the same Kafka transaction as the outputs | exactly once for consumers reading with `isolation.level=read_committed`, with one caveat at the end of a run (see [Exactly once](#exactly-once-in-a-transaction)) |

Handlers should be idempotent wherever the guarantee is at least once.

## Picking a backend

The pipeline is written once. Which backend runs it is named where the run
starts, as for any other stream:

```kotlin
import dev.surefold.kafka.Kafka
import dev.surefold.kafka.Topic
import dev.surefold.kafka.consume
import dev.surefold.kafka.mapRecordOrFail
import dev.surefold.kafka.runCommitting
import dev.surefold.stream.Forks
import dev.surefold.stream.PekkoStreams
import dev.surefold.stream.start
import org.apache.kafka.common.serialization.StringDeserializer

val placing = Kafka.consume(consumerProperties, Topic("orders"), key = StringDeserializer(), value = StringDeserializer())
    .mapRecordOrFail { record -> shop.place(record.value()).bind() }
    .runCommitting()

placing.start(Forks())                  // one virtual thread polls, handles and commits
placing.start(PekkoStreams(system))     // the same description on Pekko Streams
```

`Kafka.consume` is one `KafkaConsumer` per run, read through
`Stream.blocking`, and it is in `surefold-kafka`, which brings no backend at all.
Only the thread that polls touches the consumer. It commits what
`runCommitting()` marked handled before each poll, without waiting for the
broker's answer, so the poll is not held up by it. For the partitions it loses
in a rebalance, and when the run ends, it waits, and a run's exit completes only
after that last commit. A commit the broker refuses for good fails the run at
the next poll, as a defect. The consumer then closes within a few round trips to its
coordinator, enough for it to leave its group, rather than waiting up to
`fetch.max.wait.ms` for a fetch the broker is holding, as `KafkaConsumer.close()`
does by default. A handler slower than `max.poll.interval.ms` does not lose the
group: once half that interval passes without a poll, the consumer pauses its
partitions and polls anyway, reading nothing ahead, and resumes them when the
stream asks for the next record. A `stop()` wakes a poll that is waiting on a quiet topic.

Each partition a group assigns starts after what the group committed, or where `auto.offset.reset` says when nothing
is. `startAt = StartAt.Time(nineAm)` starts a partition with nothing committed at the first record written at or after
nine. `startAt = StartAt.Own { partitions -> store.offsets(partitions) }` seeks every assigned partition to the offset
the caller's own store gives, whatever the group committed; a partition it gives none for starts as usual.

`onAssigned = { partitions -> … }` and `onRevoked = { partitions -> … }` hear of the partitions each rebalance gives
and takes, on the polling thread, after the consumer's own handling: by the time `onRevoked` runs, what was handled on
those partitions is committed. They suit a log line, a cache to warm or state to flush. What either throws ends the
run, as a defect.

A running consumer can be asked what it holds (spec 0160). Pass `questions = ConsumerQuestions()` and ask it while
the run is open: `assignment()`, `lag()` (each partition's end offset less what the group committed) and `metrics()`,
the client's own map. Each answer is read from the run's own consumer between polls, so it waits at most one poll; a
question asked while no run holds the handle throws.

A rebalance takes every partition from a member, the ones it is given back too. By default the records a run has
handed out on them and not yet handled are then read again, by whichever member owns each partition next. With
`rebalance = Rebalance.waitFor(30.seconds)`, the member holds the rebalance until each of those records is handled,
commits them, and only then lets the group go on, so none is read again. The stream runs on meanwhile; only the poll
waits. It is off by default because a stuck handler then holds the whole group's rebalance, up to that bound.

```kotlin
Kafka.consume(consumerProperties, Topic("orders"), key = StringDeserializer(), value = StringDeserializer(),
    rebalance = Rebalance.waitFor(30.seconds))
```

`Kafka.subscribe` is Pekko's own Kafka connector, in `surefold-kafka-pekko`: a
backpressured prefetch, commits batched by its committer sink, and a `stop()`
that drains. Pekko runs it and nothing else does, and it ends on
`runCommitting(settings)`.

```kotlin
dependencies {
    // Any backend: surefold-stream and kafka-clients come with it, and the backend is the service's own choice.
    implementation("dev.surefold:surefold-kafka:0.17.0")
    // Or Pekko's connector: surefold-kafka, surefold-stream-pekko and pekko-connectors-kafka come with it.
    implementation("dev.surefold:surefold-kafka-pekko:0.17.0")
}
```

`surefold-kafka` brings `kafka-clients` 4.3, the client Confluent 8.3's
serializers are built on, which needs a broker at 2.1 or later. Pekko's
connector is built against 3.8 and runs on 4.3: its own Kafka 4 build is a
2.0 milestone.

| | `Kafka.consume` | `Kafka.subscribe` |
|---|---|---|
| Runs on | Forks, Pekko, TestStreams | Pekko |
| Ends on | `runCommitting(): Run<E, Long>`, the count of elements | `runCommitting(settings): Run<E, Done>` |
| Commits | on the polling thread: sent before each poll without waiting, waited for on a rebalance and on close | through the connector's committer sink, in batches |
| `mapParRecord`, `divertLefts` | yes, on Forks as a window of bodies in flight | yes |
| `restartOnDefect` | yes, on every backend; a restart closes the failed consumer before it opens the next | yes |

## Operators

| Call | Answers |
|---|---|
| `Kafka.consume(properties, vararg topics, key: Deserializer<K>, value: Deserializer<V>)` | `Stream<Nothing, Committed<ConsumerRecord<K, V>>>` on any backend |
| `Kafka.consume(properties, vararg topics, key: Decoder<K>, value: Decoder<V>)` | the same over bytes, each record decoded in the stream: `Committed<Either<DecodeError, ConsumerRecord<K, V>>>` |
| `Kafka.consume(properties, Topics.matching(regex), key, value)`, either form | the same over every topic whose whole name `regex` matches, those created while it runs too: it finds them as it refreshes its metadata, every `metadata.max.age.ms` (five minutes unless set) |
| `Kafka.read(properties, topic, partition, key, value, from, until)` | one partition, assigned rather than subscribed, so no rebalance can take it away; `from` is `Committed`, `Earliest`, `Latest`, an `Offset` or a `Time`, the first record written at or after it, and `until` is `Never` or `EndAtStart`. With no `group.id`, nothing is committed |
| `Kafka.read(properties, topic, partitions = listOf(0, 1, 2), key, value, from, until)` | the same over many partitions, one consumer assigned them all: each starts at `from` and stops at `until` on its own, records of one partition keep their order, and the read ends once all have ended |
| `Kafka.subscribe(settings, vararg topics)` and its `Decoder` form | the same two, through Pekko's connector |
| `mapRecord`, `mapRecordOrFail`, `mapParRecord`, `mapParRecordOrFail`, `filterRecord`, `mapConcatRecord` | surefold-stream's operator of the same stem, with the body on the value and the offset carried. `Record` because the same names beside surefold-stream's would be ambiguous |
| `groupedRecord(n, within)` | up to `n` records, or what came within `within`, as one `Committed<List<A>>`; see [Reading in batches](#reading-in-batches) |
| `mapParPartitioned(parallelism) { value -> }` | `mapParRecord` in order only within each partition, through `mapParKeyed`: a slow record holds back only its own partition's later records. Each partition's offsets still commit in order. On Forks and Pekko; Actors and TestStreams keep full order |
| `divertLefts(to: (L) -> Unit)` | each `Left` to a function, in order, before anything after it moves on; a throw from it is a defect and the record is not committed |
| `absolve()` | the first `Left` ends the run `Failed` |
| `publishTo(producer) { a -> record }`, `publishRecord(producer) { value -> record }` | each element sent, and passed on in order once the broker has it; see below |
| `publishBatch(producer) { value -> record }` | each batch sent through `producer.publishAll`, and each record passed on as `Committed<Either<PublishFailed, A>>`; see [What happens when a write in a batch fails](#what-happens-when-a-write-in-a-batch-fails) |
| `transacted(producer) { value -> records }`, `runTransactionally(...)` | each batch's outputs and offsets in one transaction; see *Exactly once* |
| `runCommitting()` / `runCommitting(settings)` | the run, committing each offset once its record's element reaches the end; `runCollect`, `runFold` and `runWith` over `Committed` do not compile, and each source's records end on their own one |

Every body runs with `kafka.topic`, `kafka.partition` and `kafka.offset` on
its log lines.

## Reading in batches

`groupedRecord` hands a body a list of records, for work that is cheaper in
bulk, such as one insert for many rows:

```kotlin
import dev.surefold.kafka.groupedRecord
import kotlin.time.Duration.Companion.milliseconds

Kafka.consume(consumerProperties, Topic("orders"), key = StringDeserializer(), value = StringDeserializer())
    .groupedRecord(500, 100.milliseconds)
    .mapRecord { records -> db.insertAll(records.map { it.value() }) }
    .runCommitting()
```

- **A batch commits as a whole.** It reaches `runCommitting` as one element,
  every record in it is marked handled, and each partition's offset moves to
  the last of its records in the batch.
  It works the same through `Kafka.subscribe` and `runCommitting(settings)`,
  and inside `transacted`.
- **A record still expanding** under `mapConcatRecord` when a batch ends is
  committed with the batch its last element lands in.
- **A batch's body has no `kafka.*` annotations** on its lines, and runs
  outside any record's trace, since its records may each come from a
  different one.

## Writing to Kafka

A `Producer` is one `KafkaProducer`, opened once and shared: the client is
thread-safe, and it batches what every caller sends. Whoever opens it closes
it, which sends what is pending.

```kotlin
import dev.surefold.kafka.Kafka
import dev.surefold.kafka.Topic
import dev.surefold.kafka.deadLetters
import dev.surefold.kafka.divertLefts
import dev.surefold.kafka.producer
import dev.surefold.kafka.publishRecord
import dev.surefold.kafka.record
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer

val orders = Kafka.producer(producerProperties, key = StringSerializer(), value = orderSerializer)
val bytes = Kafka.producer(producerProperties, key = ByteArraySerializer(), value = ByteArraySerializer())

Kafka.consume(consumerProperties, Topic("carts"), key = Decoder.string(), value = carts)
    .divertLefts(bytes.deadLetters(Topic("carts.dead")))
    .mapRecord { cart -> cart.value().checkout() }
    .publishRecord(orders) { order -> Topic("orders").record(order.id, order) }
    .runCommitting()
```

- **`publishRecord` commits after the output.** Up to `inFlight` (256)
  records wait on the broker at once, so the producer fills its batches. Each
  element passes on in the order it came, once it and every one before it are
  acknowledged, so an offset is never committed ahead of what it produced. A
  stop or a restart sends the rest again: at-least-once.
- **`publishTo`** is the same on any stream, for a source that is not Kafka.
- **A record the producer gives up on is a defect**, for `restartOnDefect`.
  The client has already retried what it could, for up to
  `delivery.timeout.ms`. `producer.publish(record)` answers
  `Either<PublishFailed, Published>` for a caller that wants to route it.
- **`deadLetters(topic)`** is `divertLefts`' function: the unreadable record's
  bytes and headers, plus `surefold.dead-letter.topic`, `.partition`, `.offset`,
  `.part` and `.cause`. It returns once the letter is acknowledged, so the bad
  record is never committed past a letter that was not written.
- **A producer answers questions** (spec 0160). `producer.flush()` sends what is
  lingering at once and returns when the broker has it. `producer.partitionsFor(topic)`
  names a topic's partitions in order, each with its leader. `producer.metrics()` is
  the client's own map, all of it.

## What happens when a write in a batch fails

`publishRecord` fails the stream when the producer gives up on a record.
`publishBatch` hands the outcome to the stream instead, one per record, so
what comes after picks what to do:

```kotlin
import dev.surefold.kafka.absolve
import dev.surefold.kafka.publishBatch

Kafka.consume(consumerProperties, Topic("carts"), key = StringDeserializer(), value = carts)
    .mapRecord { cart -> cart.value().checkout() }
    .publishBatch(orders) { order -> Topic("orders").record(order.id, order) }
    .absolve()                          // or .divertLefts { failed -> ... }
    .runCommitting()
```

- **A batch is up to 500 records, or what came within 100 ms**, sent by
  `producer.publishAll(records)`. Each record is handed to the producer
  without waiting on the one before, and one batch is in flight at a time.
- **Once a record fails, the rest of its batch is not sent.** Each comes back
  `PublishFailed` with a `PublishOmitted` cause, whose own cause is the first
  failure, so the outputs keep their order. A record already handed to the
  producer when the failure is seen keeps its own outcome, and may be
  written. In practice omission is for a failure `send` sees at once, such as
  a record too large or a serializer that throws; one the broker answers
  later usually arrives after the whole batch was handed over.
- **Offsets move up to the first failure.** Each record reaches
  `runCommitting` on its own, in order. `absolve()` ends the run `Failed`
  with the `PublishFailed`, and only the records before it are committed: a
  restart sends the failed record and everything after it again.
- **`divertLefts` goes on past it**, to dead-letter or to skip. The failed
  and omitted records are handed to its function, and their offsets commit
  with the next record written on their partition.
- **The producer has already retried** what it could, for up to
  `delivery.timeout.ms`, so a `Left` is final.

## Exactly once, in a transaction

`publishRecord` is at-least-once: a crash after an output is acknowledged
but before its offset is committed writes it again. `transacted` commits
the outputs and the offset in one Kafka transaction instead, so a reader
with `isolation.level=read_committed` sees each record's outputs once.

```kotlin
import dev.surefold.kafka.transacted
import dev.surefold.kafka.transactionalProducer

val orders = Kafka.transactionalProducer(producerProperties, transactionalId = "checkout-1", StringSerializer(), orderSerializer)

Kafka.consume(consumerProperties, Topic("carts"), key = StringDeserializer(), value = carts)
    .mapRecord { cart -> cart.value().checkout() }
    .transacted(orders) { order -> listOf(Topic("orders").record(order.id, order)) }
    .restartOnDefect(Schedule.exponential(100.milliseconds))
    .runFold(0L, Long::plus)
```

- **One transaction per batch.** Records are batched by `groupedWithin`, up
  to 500 or what arrives within 100 ms. Each batch is one transaction: every
  output is sent, then the batch's offsets with the consumer's group
  metadata, then the transaction commits. Transactions run one at a time,
  so a transaction holds exactly the outputs of the offsets it commits.
- **A failure aborts.** The transaction's outputs are never seen by a
  `read_committed` reader, and its offsets are not committed. It is a
  defect, so `restartOnDefect` after `transacted` reads again from the last
  committed offsets. `runTransactionally` is `transacted` run to its count,
  with no restart.
- **The consumer commits nothing** for these records; the transaction does.
  Only `Kafka.consume`'s records can be transacted: `Kafka.subscribe`'s carry
  no group metadata, and are refused.
- **The transactional id** is one per instance and stable across restarts,
  so a restarted instance fences the one it replaces.
- **Fencing.** A transaction's offsets are committed with the group metadata
  of the member that polled its batch, so a batch that straddles a rebalance
  is refused by the broker rather than committing a partition this consumer
  has lost.
- **A run's last transaction** can commit after its consumer has left the
  group, when the run ends by `take`, `stop()` or a restart giving up. That
  transaction is committed with the group id alone, which the broker accepts
  without a generation check, so if the group rebalances at that moment a new
  owner may read the last batch again.

## Meters

`Kafka.consume`, the producers and `runTransactionally` record through surefold's
metrics API, so installing `surefold-micrometer` exports them, and with no backend
bound they cost nothing measurable (`surefoldForks` against `surefoldForksMetered` in
`surefold-kafka-benchmarks`). A consumer records to the metrics in scope, with the
`metricTagged` tags bound, where it is described; a producer where it is
opened. Every meter carries `group` (consumers) and `client`; a consumer whose
properties name no `client.id` is given one, `surefold-consumer-<group>-<n>`, so two
consumers of one group in one process keep their own gauges. A producer's `client`
is empty where its properties name none. Durations are in seconds.

| Meter | Kind | Tags | Recorded |
|---|---|---|---|
| `surefold.kafka.consumer.records`, `.bytes` | counter | `topic`, `partition` | each poll |
| `surefold.kafka.consumer.lag` | gauge | `topic`, `partition` | after each poll, from the client's last fetch; 0 once the partition is lost |
| `surefold.kafka.consumer.latency` | histogram | `topic`, `partition` | when a record is marked handled: now less its timestamp |
| `surefold.kafka.consumer.in.flight` | gauge | | records handed to the stream and not yet handled, at each poll |
| `surefold.kafka.consumer.poll`, `.commit`, `.close` | timer | | around each; `commit` is the commit a rebalance or close waits for |
| `surefold.kafka.consumer.commit.failures` | counter | | a commit the broker refused |
| `surefold.kafka.consumer.rebalances` | counter | `kind`: `assigned` or `revoked` | each rebalance |
| `surefold.kafka.consumer.assigned`, `.paused` | gauge | | the partitions owned, and those paused while the stream is slow |
| `surefold.kafka.producer.records`, `.bytes`, `.failures` | counter | `topic` | each acknowledgement, or each record given up on |
| `surefold.kafka.producer.ack` | timer | `topic` | send to acknowledgement |
| `surefold.kafka.dead.letters` | counter | `topic` read from | each `deadLetters` write |
| `surefold.kafka.transactions`, `.aborts` | timer, counter | | each `runTransactionally` commit, and each abort |

`Kafka.read` records none yet.

## A registry that is down is not a bad record

A `Decoder` wraps any Kafka `Deserializer`, Avro and a schema registry
included, and is told which throws are transient. Those stay defects, so
`restartOnDefect` backs off and reads the same record again. Every other
throw is a `DecodeError` to divert or absolve. There is no default rule,
because the obvious one is wrong for Confluent's deserializer: a registry
answering 5xx throws a `RestClientException`, not an `IOException`, and a
rule that missed it would send every record to the dead-letter topic.

```kotlin
import dev.surefold.kafka.Decoder
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException
import io.confluent.kafka.serializers.KafkaAvroDeserializer
import java.io.IOException

fun registryDown(thrown: Throwable): Boolean =
    generateSequence(thrown) { it.cause }
        .any { it is IOException || (it is RestClientException && it.status >= 500) }

fun orders(configured: KafkaAvroDeserializer): Decoder<Any> = Decoder(configured, transient = ::registryDown)
```

## Administering topics

`Kafka.admin(properties)` is the few `Admin` calls a service's start-up and a runbook make (spec 0162), each blocking
until the cluster answers. Whoever opens it closes it.

```kotlin
Kafka.admin(adminProperties).use { admin ->
    admin.ensure(Topic("orders"), partitions = 6, replicas = 3, config = mapOf("retention.ms" to "604800000"))
    admin.topics()                      // every topic but Kafka's internal ones
    admin.describe(Topic("orders"))     // Either<NoSuchTopic, TopicDescription>
    admin.delete(Topic("orders.old"))   // Either<NoSuchTopic, Unit>
}
```

`ensure` creates the topic, or grows it to `partitions` if it has fewer; it refuses to shrink one, which Kafka cannot
do. It leaves an existing topic's config as it is and answers how it differs from `config`, in `Ensured.differing`, for
the caller to act on. A topic the cluster does not have is `NoSuchTopic`; anything else the cluster answers is thrown.

For a runbook, `admin.groups()` lists the consumer groups, `admin.lag(group)` answers how far behind each partition it
has committed for is, and `admin.resetOffsets(group, topic, to = From.Time(nineAm))` moves its offsets on every
partition of `topic` for its next run to start there. Kafka moves a group's offsets only while it has no members, so a
group still running is `GroupActive`, and its offsets stay where they were.

## Testing against a broker

`surefold-kafka-test` runs a Kafka broker in the test JVM, so a test needs no
Docker. Add it as a test dependency. `KafkaBroker` has helpers that send, read,
create topics and wait for what a group committed. `send` and `read` use strings;
for a topic of Avro, bytes or numeric keys, `send(topic, key, value,
keySerializer, valueSerializer)` writes one record and `records(topic,
keyDeserializer, valueDeserializer)` reads every partition up to its end, with the
serializers the service already has. A surefold-junit suite shares
one broker across its tests as a `Resource`:

```kotlin
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.kafka.KafkaBroker
import dev.surefold.kafka.Topic
import dev.surefold.kafka.kafkaBroker

object OrdersSuite : SharedSuite<KafkaBroker>() {
    override val shared = kafkaBroker()

    init {
        test("an order is placed once") { kafka ->
            val orders = Topic("orders")
            kafka.send(orders, "o-1")
            // … run the pipeline with bootstrap.servers set to kafka.bootstrap …
            kafka.committed(group = "placing", topic = orders) shouldBe 1L
        }
    }
}
```

On plain Jupiter, register the broker as an extension. It starts before the
class's first test and stops after its last:

```kotlin
class OrdersTest {
    companion object {
        @JvmField @RegisterExtension
        val kafka = KafkaBroker()
    }
}
```

### Reading a topic in a test

`Kafka.reading` reads what a service wrote, from any broker: this module's, or
one in a container, given its bootstrap string. It never joins a group or
commits, so reading changes nothing the service's groups see. Each call is a
snapshot. It reads every partition up to the end offset it had when asked, so it
answers at once, empty for an empty topic, and has no timeout of its own. A
test waiting for a record asks again.

```kotlin
Kafka.reading(Topic("orders"), kafka.bootstrap, StringDeserializer(), OrderDeserializer()).use { orders ->
    orders.values()            // every value, each partition oldest first
    orders.records()           // the ConsumerRecords, keys and headers too
}

committedOffset(kafka.bootstrap, group = "placing", Topic("orders"))   // summed over partitions; 0 for none
committedOffsets(kafka.bootstrap, group = "placing", Topic("orders"))  // partition to offset
```

A record its deserializers cannot read throws `IllegalStateException`, naming
the record as `topic-partition@offset`, with the deserializer's exception as its
cause. To read junk on purpose, read with a `ByteArrayDeserializer`.
