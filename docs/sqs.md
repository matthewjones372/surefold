# SQS

**An SQS message is deleted only after the work on it is done.** A read is a
`Stream` of `Received` elements, each carrying its message's receipt, and the
only way to run one is `runDeleting`. Deleting before the work, or forgetting
to, does not compile.

Everything below is in `dev.surefold.sqs`, in `surefold-sqs`, which depends on
`surefold-stream` and the AWS SDK's `sqs` client on the JDK's own HTTP client.
A read is an ordinary [surefold-stream](stream.md) `Stream`, so the operators,
backends and test support described there apply here too.

## Delivery at a glance

| Pipeline | Message deleted | Guarantee |
|---|---|---|
| `Sqs.consume(…)…runDeleting()` | once its element reaches the end of the stream | at least once: a crash, a stop or a lapsed timeout before the delete hands it out again |
| `…sendTo(sqs) { … }.runDeleting()` | once what it sent is on the other queue | at least once: a message can be sent twice |
| `…divertLefts { … }` | never: it comes back, and the queue's redrive policy decides when it stops | the `Left` is handed over each time it is read |

Handlers should be idempotent.

## Reading a queue

<!-- sqs-guide -->
```kotlin
import dev.surefold.fail.Either
import dev.surefold.logWarn
import dev.surefold.sqs.Queue
import dev.surefold.sqs.Sqs
import dev.surefold.sqs.consume
import dev.surefold.sqs.divertLefts
import dev.surefold.sqs.mapMessage
import dev.surefold.sqs.message
import dev.surefold.sqs.runDeleting
import dev.surefold.sqs.sendTo
import dev.surefold.stream.Run
import software.amazon.awssdk.services.sqs.SqsClient

data class Order(val id: String, val customer: String)

fun shipping(
    sqs: SqsClient,
    orders: Queue,
    shipments: Queue,
    parse: (String) -> Either<String, Order>,
): Run<Nothing, Long> =
    Sqs.consume(sqs, orders)
        .mapMessage { message -> parse(message.body()) }
        .divertLefts { unreadable -> logWarn("not an order: $unreadable") }
        .sendTo(sqs) { received -> shipments.message(received.value.id, mapOf("customer" to received.value.customer)) }
        .runDeleting()
```

`Sqs.consume` long-polls `ReceiveMessage` for up to `waitTime` (20 seconds)
and up to `maxMessages` (10) at a time, asking for every attribute, and is read
through `Stream.blocking`. The client is the caller's: a run never closes it.
`runDeleting()` marks each message handled as its element reaches the end, and
the reader deletes what is marked, in batches, before each receive and when the
run ends; a run's exit completes after that last delete. A `stop()` interrupts a
long poll that is waiting on a quiet queue.

`mapMessage`, `mapMessageOrFail`, `mapParMessage`, `divertLefts` and `absolve`
are surefold-stream's operators with the receipt carried through. A message
whose element fails, or that `absolve` stops at, is not deleted.

## Visibility

Each receive hides its messages for `visibility` (30 seconds). While a message
is handed out and not yet handled or diverted, a virtual thread renews that
every half `visibility`, so a handler slower than the timeout does not see its
message twice. `extendVisibility = false` turns the renewals off, and a message
then comes back once its timeout lapses, handled or not.

`divertLefts(nack = true)` makes a diverted message visible again at once rather
than when its timeout lapses. Either way it is not deleted: give the queue a
redrive policy, or a message that never reads will be read forever.

## Sending

`Queue.message(body, attributes, groupId, dedupId)` names where a message goes;
a group or deduplication id on a queue whose name does not end in `.fifo` is
refused there. `SqsClient.send` answers `Either<SendFailed, Sent>`, and
`SqsClient.sendAll` sends up to ten in one `SendMessageBatch` per queue and
answers per message: an entry SQS refuses is an `SqsRefused` for that message
alone.

In a stream, `sendTo` passes each element on, in order, once SQS has it, with up
to `inFlight` (16) sends at once. A send that fails is a defect, for
`restartOnDefect`. On a FIFO queue a group keeps its order only at
`inFlight = 1`. `sendBatch` groups up to ten, or what came within `within`, and
passes each element on as the outcome of its own message, so a refused one is a
`Left` that `absolve` or `divertLefts` decides on. To publish what a queue reads to an SNS topic, see
[`surefold-sns`](sns.md).

## On Pekko

`Sqs.consume` runs on Pekko too, one long poll at a time on one thread. A Pekko
service reading a busy queue can read it through Pekko Connectors' `SqsSource`
instead, with `surefold-sqs-pekko`: several receives in flight at once, their
messages buffered, and deletes batched and sent asynchronously by `SqsAckSink`.
The elements are the same `Received`, so every operator above applies.

<!-- sqs-pekko -->
```kotlin
import dev.surefold.sqs.Queue
import dev.surefold.sqs.Sqs
import dev.surefold.sqs.mapMessage
import dev.surefold.sqs.runDeleting
import dev.surefold.sqs.source
import dev.surefold.stream.PekkoStreams
import dev.surefold.stream.Running
import dev.surefold.stream.start
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.connectors.awsspi.PekkoHttpClient
import org.apache.pekko.stream.connectors.sqs.SqsSourceSettings
import software.amazon.awssdk.services.sqs.SqsAsyncClient

fun onPekkoHttp(system: ActorSystem): SqsAsyncClient =
    SqsAsyncClient.builder().httpClient(PekkoHttpClient.builder().withActorSystem(system).build()).build()

fun shipping(
    system: ActorSystem,
    client: SqsAsyncClient,
    orders: Queue,
    ship: (String) -> Unit,
): Running<Nothing, Done> =
    Sqs.source(client, orders, SqsSourceSettings.create().withParallelRequests(4))
        .mapMessage { message -> ship(message.body()) }
        .runDeleting(client, orders)
        .start(PekkoStreams(system))
```

The connector takes `SqsAsyncClient`; on the connector's own Pekko HTTP
transport, as here, no Netty comes with it. The client is the caller's. A run's
`stop()` drains: no receive starts after it, and what was handed out reaches the
end and is deleted. Messages the connector received but never handed out stay
in flight until their timeout, then come back.

What differs from `Sqs.consume`:

- **No renewal.** The connector keeps no message's visibility. Set the queue's,
  or the source settings', timeout longer than buffering and handling together.
  A message that lapses is received again, and the first copy's delete then
  fails the run with `ReceiptHandleIsInvalidException`, where `Sqs.consume`
  counts it and goes on.
- **One end each.** Messages from `Sqs.source` end on
  `runDeleting(client, queue)`, and those from `Sqs.consume` on `runDeleting()`.
  The other one is a defect naming the right one.
- **No meters yet.** `surefold.sqs.*` is recorded by `Sqs.consume` and the
  sends only.

## Meters

`Sqs.consume`, `send`, `sendAll`, `sendTo` and `sendBatch` record through surefold's
metrics API, so installing `surefold-micrometer` exports them, and with no backend
bound they cost nothing. A read, `sendTo` and `sendBatch` record to the metrics in
scope, with the `metricTagged` tags bound, where they are described; `send` and
`sendAll` where they are called. Every meter carries `queue`, the queue's name.
Durations are in seconds.

| Meter | Kind | Tags | Recorded |
|---|---|---|---|
| `surefold.sqs.consumer.received`, `.bytes` | counter | | each receive |
| `surefold.sqs.consumer.receive` | timer | | around each `ReceiveMessage` |
| `surefold.sqs.consumer.redelivered` | counter | | a message whose `ApproximateReceiveCount` is past one |
| `surefold.sqs.consumer.latency` | histogram | | when a message is handled: now less its `SentTimestamp` |
| `surefold.sqs.consumer.in.flight` | gauge | | messages handed to the stream and not yet handled or dropped |
| `surefold.sqs.consumer.deleted` | counter | | each message deleted |
| `surefold.sqs.consumer.delete.failures` | counter | `code` | each delete SQS refused |
| `surefold.sqs.consumer.extended` | counter | | each visibility renewal |
| `surefold.sqs.consumer.extend.failures` | counter | `code` | each renewal refused, or the exception's name where the call failed |
| `surefold.sqs.consumer.nacks` | counter | | each `divertLefts(nack = true)` |
| `surefold.sqs.producer.sent` | counter | | each message SQS accepted |
| `surefold.sqs.producer.failures` | counter | `code` | each message refused, or the exception's name where the call failed |
| `surefold.sqs.producer.send` | timer | | each request, call to answer |

`latency` compares SQS's clock with the service's, so skew between them shows up
in it. `delete.failures` tagged `ReceiptHandleIsInvalid` is the one to alert on:
a message was handled but not deleted, and will be handled again. The queue's
depth is not here; CloudWatch has `ApproximateNumberOfMessagesVisible`.

## Testing against a queue

`surefold-sqs-test` runs SQS in the test JVM on ElasticMQ, so a test needs no
Docker. A surefold-junit suite shares one `SqsQueues` as its resource; a Jupiter
class registers `SqsQueues()` with `@RegisterExtension` instead. Each starts on
a port the system picks, so suites run in parallel.

<!-- sqs-testing -->
```kotlin
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.sqs.Depth
import dev.surefold.sqs.Sqs
import dev.surefold.sqs.SqsQueues
import dev.surefold.sqs.consume
import dev.surefold.sqs.runDeleting
import dev.surefold.sqs.sqsQueues
import dev.surefold.stream.Exit
import dev.surefold.stream.Forks
import dev.surefold.stream.run
import dev.surefold.stream.take
import kotlin.time.Duration.Companion.seconds

object OrdersSuite : SharedSuite<SqsQueues>() {
    override val shared = sqsQueues()

    init {
        test("every order read is deleted") { sqs ->
            val orders = sqs.create("guide-orders", deadLetters = sqs.create("guide-orders-dlq"))
            sqs.send(orders, "o-1", "o-2")

            Sqs.consume(sqs.client, orders, waitTime = 1.seconds).take(2).runDeleting().run(Forks())
                .toCompletableFuture().join() shouldBe Exit.Done(2L)

            sqs.depth(orders) shouldBe Depth(visible = 0, inFlight = 0)
        }
    }
}
```

`client` is an `SqsClient` pointed at the queue, closed when the resource is
released. `create` takes `fifo`, `visibility`, `deadLetters` and `maxReceives`,
`send` writes bodies in order, `receive` reads without deleting, and `depth`
answers how many messages are visible and in flight. ElasticMQ brings Pekko 1.5
onto the test classpath.

A fake `SqsClient` is still how a test makes a call fail: ElasticMQ cannot be
told to.
