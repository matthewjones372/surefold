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
`Left` that `absolve` or `divertLefts` decides on.

## Not here yet

A queue in the test JVM, meters, a Pekko connector module and SNS are each their
own spec. Until the test module lands, a fake `SqsClient` is what this module's
own suites run against.
