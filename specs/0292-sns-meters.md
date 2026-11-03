# 0292 — surefold-sns's own meters

## Problem

`surefold-sns` (0287) records nothing. A service publishing with `publishTo`
or `publishBatch` cannot see how many messages it published, how many SNS
refused and why, or how long a publish took. `surefold-sqs` records exactly
this for its sends as `surefold.sqs.producer.*` (0289), and `surefold-kafka`
for its producer as `surefold.kafka.producer.*` (0163).

## Not doing

- **No new dependency.** Recorded through `surefold`'s metrics API, as 0289.
- **No delivery meters.** What happens after SNS accepts a message, delivery to
  each subscriber, is SNS's and is in CloudWatch.
- **No meters in `surefold-sns-test`.** The fake topic records nothing; a test
  reads `published(topic)`.

## Shape

Every meter is tagged `topic`, the topic's name (the last segment of its ARN),
and whatever `metricTagged` adds.

| Meter | Kind | Read from |
|---|---|---|
| `surefold.sns.publisher.published` | counter | each message SNS accepted |
| `surefold.sns.publisher.failures` | counter, tagged `code` | each refused entry's code, or the exception's name where the SDK threw |
| `surefold.sns.publisher.publish` | histogram, s | each request, call to answer: one per `Publish`, one per topic per `PublishBatch` |

`SnsPublisher.publish` and `publishAll` record to the meters in scope where
they are called. `publishTo`, `publishBatch` and `publishMessageBatch` take them
where the stream is described, as `sendTo` does, because their publishes run on
other threads.

## Why this shape

The same three meters `surefold.sqs.producer.*` has, with SNS's own noun, so one
dashboard panel reads both and a service moving a fan-out from one to the other
keeps its alerts. The alternative, counting bytes and batch sizes too, adds
meters nobody has asked for yet; a later spec can add them.

## Stack

- [ ] **`spec-0292-publisher`** — the three meters, in `SnsPublisher` and the stream operators.
      Done when: a batch with one refused entry records one failure tagged with its code, and `publishTo` records where it was described.
- [ ] **`spec-0292-docs`** — "Meters" in `docs/sns.md`, the list in `docs/observability.md`.
      Done when: `AgentDocsSuite` and the guide suites pass.

## Acceptance

```bash
./gradlew :surefold-sns:build
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **`publisher` or `producer` in the name?** Recommended: `publisher`, SNS's
   word and `surefold-sns`'s class, at the cost of differing from
   `surefold.sqs.producer`.
2. **Tag by topic name or ARN?** Recommended: name, as 0289 tags queues; two
   topics of one name in two regions need `metricTagged`.
3. **Share code with `surefold-sqs`'s `ProducerMeters`?** Recommended: no. They
   are a dozen lines each, and sharing would make it public in `surefold-sqs`.
4. **Count the messages a `publishMessageBatch` deletes?** Recommended: no;
   `surefold.sqs.consumer.deleted` already does.
