# SNS

**An element passes on only once SNS has its message.** `surefold-sns`
publishes to a topic from any [surefold-stream](stream.md) `Stream`, one
message at a time or ten to a `PublishBatch`, and answers for each message on
its own.

Everything below is in `dev.surefold.sns`, in `surefold-sns`, which depends on
[`surefold-sqs`](sqs.md) and the AWS SDK's `sns` client on the JDK's own HTTP
client. SNS pushes rather than being read, so there is no consumer here: a
topic is read through an SQS queue subscribed to it, with `Sqs.consume`. Turn
on raw message delivery for that subscription, so the queue holds the message
as it was published rather than SNS's JSON envelope around it.

## From a queue to a topic

<!-- sns-guide -->
```kotlin
import dev.surefold.logWarn
import dev.surefold.sns.SnsPublisher
import dev.surefold.sns.SnsTopic
import dev.surefold.sns.message
import dev.surefold.sns.publishMessageBatch
import dev.surefold.sqs.Queue
import dev.surefold.sqs.Sqs
import dev.surefold.sqs.consume
import dev.surefold.sqs.divertLefts
import dev.surefold.sqs.runDeleting
import dev.surefold.stream.Run
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sqs.SqsClient

fun announcing(sqs: SqsClient, sns: SnsClient, orders: Queue, placed: SnsTopic): Run<Nothing, Long> =
    Sqs.consume(sqs, orders)
        .publishMessageBatch(SnsPublisher(sns)) { message -> placed.message(message.body()) }
        .divertLefts(nack = true) { failed -> logWarn("not announced: $failed") }
        .runDeleting()
```

`publishMessageBatch` groups up to ten messages read from SQS, or what came
within `within` (100 ms), publishes them in one `PublishBatch`, and passes each
on with its receipt as the outcome of its own publish. A message is deleted only
once its own publish succeeded; one that SNS refused is a `Left`, here handed to
`divertLefts` and made visible again at once, so it is tried again.

## Publishing

| Call | Answers | A refusal is |
|---|---|---|
| `SnsPublisher.publish(message)` | `Either<PublishFailed, Published>` | a `Left` |
| `SnsPublisher.publishAll(messages)` | one `Either` per message, up to ten | a `Left` for that message alone, with an `SnsRefused` cause |
| `stream.publishTo(publisher) { … }` | each element, in order, once SNS has it | a defect, for `restartOnDefect` |
| `stream.publishBatch(publisher) { … }` | each element as `Either<PublishFailed, A>` | a `Left` for `absolve` or `divertLefts` |

`SnsTopic.message(body, attributes, groupId, dedupId)` names where a message
goes; a group or deduplication id on a topic whose name does not end in `.fifo`
is refused there. Attributes are strings. `publishTo` runs up to `inFlight`
(16) publishes at once, so on a FIFO topic a group keeps its order only at
`inFlight = 1`.

`SnsPublisher` publishes through a client it does not own: closing the client is
the caller's.

## Not here yet

Meters, a fake topic for a service's own tests, binary and number attributes,
and unwrapping SNS's envelope on a subscription without raw delivery.
