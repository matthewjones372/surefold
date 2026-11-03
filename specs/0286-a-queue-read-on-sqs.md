# 0286 — A queue read on SQS

## Problem

A service on AWS that takes its work from SQS has no surefold way to read it. It
writes its own `ReceiveMessage` loop, deletes by hand, and gets none of what
`surefold-kafka` gives a topic: a `Stream` on any backend, a message settled only
once its element reaches the end, and a sink that passes elements on once sent.

## Not doing

- **No SNS.** Publishing to a topic is spec 0287.
- **No queue in the test JVM** (ElasticMQ, as `surefold-kafka-test` is to Kafka),
  no meters, no Pekko connector module. Each is its own spec once this lands.
- **No queue admin.** Creating queues and redrive policies is the IaC's job.
- **No shared settle type with Kafka.** See Open questions 1.

## Shape

New module `surefold-sqs` in `integrations/`: `surefold-stream` and the AWS SDK
2.55.6 `sqs` on its URL-connection client, as `surefold-cluster-aws` does.

```kotlin
val handled: Run<ShopError, Long> =
    Sqs.consume(client, Queue(url))                      // Stream<Nothing, Received<Message>>
        .mapMessage { it.body() }
        .mapMessageOrFail { order -> shop.place(order).bind() }
        .runDeleting()                                   // deletes each message whose element reached the end

val sent: Stream<E, Order> =
    orders.sendTo(sqsProducer, Queue(url)) { order -> order.toJson() }   // passed on once SQS has it
```

- `Sqs.consume` is `Stream.blocking` over long-polled `ReceiveMessage` (`waitTime`
  20 s, `maxMessages` 10 by default). `wake` cancels the in-flight receive.
- `Received<A>` holds the value and a receipt handle it never hands out.
  `mapMessage`, `mapMessageOrFail`, `mapParMessage`, `divertLefts`, `absolve` as
  the `Committed` operators do.
- `runDeleting()` marks a message handled as its element reaches the end. The
  polling thread deletes what is marked with `DeleteMessageBatch` before each
  receive, and on close. Unlike Kafka there is no ordering: each message settles
  on its own.
- A message whose element fails or is diverted is not deleted. It returns after
  the queue's visibility timeout and, past `maxReceiveCount`, goes to the
  queue's own dead-letter queue. `nack` puts it back at once (visibility 0).
- While a message is in flight, its visibility is extended before it lapses
  (`ChangeMessageVisibilityBatch`), so a slow handler does not see it twice.
- `sendTo` and `sendBatch` mirror `publishTo` and `publishBatch`; a batch of up
  to 10 goes in one `SendMessageBatch`, and a message SQS refuses comes back a
  `Left` for its own element. FIFO queues take a `groupId` and `dedupId`.

## Why this shape

It copies `surefold-kafka` where the brokers agree and differs only where SQS
does: per-message deletion instead of offsets, and visibility instead of
rebalance. The alternative, a generic `Queue` interface over both, would hide the
part that matters most — when a message is settled — behind the weaker of two
answers. Recommended: a module of its own, with the Kafka names.

## Stack

- [ ] **`spec-0286-module`** — the module, `Queue`, `sendTo`, `sendBatch`.
      Done when: sends against a fake `SqsClient` pass, `NoOtherDependenciesTest` holds.
- [ ] **`spec-0286-consume`** — `Sqs.consume`, `Received`, operators, `runDeleting`.
      Done when: on Forks and TestStreams, only messages that reached the end are deleted.
- [ ] **`spec-0286-visibility`** — extend in-flight visibility, `nack`.
      Done when: a handler slower than the timeout sees its message once.
- [ ] **`spec-0286-guide`** — `docs/sqs.md`, modules table, llms.txt, SKILL.md.
      Done when: the guide's fences compile and `AgentDocsSuite` passes.

## Acceptance

```bash
./gradlew :surefold-sqs:build
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Lift `Committed` into `surefold-stream`** so SQS reuses Kafka's operators?
   Answered: not yet. `Received` of its own now, and a later spec merges the
   two once both exist and their differences are known.
2. **Tests against a fake client or ElasticMQ?** Answered: a fake `SqsClient`
   here, as `AwsSuite` does, and ElasticMQ in the follow-up test-module spec.
3. **Visibility extension on by default?** Answered: yes, with
   `extendVisibility = false` to turn it off. Built: renewed every half of an
   explicit `visibility` (30 s by default) sent with each receive, rather than
   the queue's own timeout; kept so on review, as it needs no
   `GetQueueAttributes` call or permission.
4. **Sync or async SDK client?** Answered: sync, since Forks runs it on a
   virtual thread and the async client would bring Netty.
