# 0290 — SQS through Pekko's connector

## Problem

`Sqs.consume` (0286) already runs on `PekkoStreams`, but as `Stream.blocking`
over the sync client: one receive at a time, on one thread, with deletes made
by the reader. A Pekko service reading a busy queue wants what Pekko
Connectors' `SqsSource` gives instead: several long polls in flight at once,
buffered and backpressured, with deletes batched asynchronously by
`SqsAckSink`. `surefold-kafka-pekko` (0056) answered the same need for Kafka by
wrapping the connector beside the neutral loop.

## Not doing

- **No change to `surefold-sqs`'s operators**, apart from the seam below.
- **No visibility renewal on this path.** The connector has no renewal loop.
  A slow handler gets a long `visibilityTimeout` in the source's settings.
- **No SNS through the connector.** `surefold-sns` stays on the sync client.

## Shape

New module `surefold-sqs-pekko` in `integrations/`: `surefold-sqs`,
`surefold-stream-pekko` and `pekko-connectors-sqs_2.13` 1.3.0, whose
`SqsAsyncClient` runs on the connector's own `aws-spi-pekko-http` rather than
Netty.

```kotlin
val shipping: Run<ShopError, Done> =
    Sqs.source(asyncClient, Queue(url), SqsSourceSettings.create().withParallelRequests(4))
        .mapMessageOrFail { message -> shop.ship(message.body()).bind() }  // surefold-sqs's operators, unchanged
        .runDeleting(asyncClient, SqsAckSettings.create())                  // ends on the connector's SqsAckSink

shipping.start(PekkoStreams(system))   // only Pekko runs it; Sqs.consume runs anywhere
```

- `Sqs.source` is `Stream.hooked` over `SqsSource.create`, so a run's `stop()`
  drains: no new receives, and what was handed out finishes and is deleted.
- Each element is a `Received` whose settle seam (below) records a
  `MessageAction` rather than a handle. `runDeleting(client, settings)` maps
  each to `MessageAction.delete` and ends on `SqsAckSink`. `divertLefts(nack =
  true)` becomes `changeMessageVisibility(0)` through the same sink.
- An element from one source ending on the other's `runDeleting` is a defect
  naming the right one, as `Committed` does across Kafka's two modules.

**The seam.** `Received`'s receipt is internal today. This spec adds an
`@SqsSpi` interface, `Settle { handled(); dropped(nack) }`, that a module can
construct a `Received` with, the way `@KafkaSpi`'s `Handle` lets
`surefold-kafka-pekko` bring the connector's offsets in.

## Why this shape

Kafka's split, again: the neutral reader for any backend, and the connector for
Pekko services that want its parallel receives and asynchronous batched acks.
The alternative, giving `Sqs.consume` parallel receives itself, would mean a
second reader with its own buffering, which is the connector's job.
Recommended: a module of its own (Open questions 1).

## Stack

- [ ] **`spec-0290-seam`** — `@SqsSpi Settle`, and `Received` built from it; `Sqs.consume` moves onto it.
      Done when: every `surefold-sqs` suite passes unchanged.
- [ ] **`spec-0290-module`** — `surefold-sqs-pekko`: `Sqs.source`, `runDeleting(client, settings)`, nack.
      Done when: on ElasticMQ through `SqsQueues`, only messages that reached the end are deleted, and a stop drains.
- [ ] **`spec-0290-guide`** — "On Pekko" in `docs/sqs.md`, the modules table, llms.txt, SKILL.md.
      Done when: the guide's fence compiles and `AgentDocsSuite` passes.

## Acceptance

```bash
./gradlew :surefold-sqs:build :surefold-sqs-pekko:build
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Build it at all?** `Sqs.consume` already runs on Pekko. Recommended: yes,
   but only for parallel receives and async acks; say so in the guide, and
   point everyone else at `Sqs.consume`.
2. **Which HTTP client under `SqsAsyncClient`?** Recommended: the connector's
   `aws-spi-pekko-http`, so no Netty arrives; Netty's `nio` client stays excluded.
3. **`@SqsSpi` lands twice.** 0287's batch entry already adds `@SqsSpi` for
   `carrying`. Recommended: this spec's seam entry adds the annotation, and
   whichever of the two merges second drops its copy.
4. **Connector version.** 1.3.0 is built on pekko-stream 1.1.5 and AWS SDK
   2.42; the repo resolves Pekko 1.2.1 and SDK 2.55.6. Recommended: accept the
   upgrade, as `surefold-kafka-pekko` does, with the module's suite run on the
   resolved versions.
