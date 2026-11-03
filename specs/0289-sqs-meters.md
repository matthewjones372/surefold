# 0289 — surefold-sqs's own meters

## Problem

`surefold-sqs` (0286) records nothing. A service running
`Sqs.consume(…).runDeleting()` cannot see how stale its work is, how often a
message comes back, how often a delete or a visibility renewal fails, or how
many messages it holds in flight. The reader drops failed deletes and renewals
on purpose, since SQS will redeliver, so today those failures are invisible.
`surefold-kafka` answers the same questions with `surefold.kafka.*` (0163).

## Not doing

- **No new dependency.** Recorded through `surefold`'s `counter`, `gauge`,
  `histogram` and `timed`, as 0163 does; `surefold-micrometer` exports them.
- **No SNS meters.** `surefold-sns` gets its own spec if this one is wanted.
- **No traces.** Carrying a span across a message is its own spec.

## Shape

Every meter is tagged `queue`, the queue's name (the last segment of its URL),
and whatever `metricTagged` adds around the run.

| Meter | Kind | Read from |
|---|---|---|
| `surefold.sqs.consumer.received`, `.bytes` | counter | each receive |
| `surefold.sqs.consumer.receive` | timer | around each `ReceiveMessage` |
| `surefold.sqs.consumer.redelivered` | counter | a message whose `ApproximateReceiveCount` > 1 |
| `surefold.sqs.consumer.latency` | histogram, s | now − the message's `SentTimestamp`, when it is handled |
| `surefold.sqs.consumer.in.flight` | gauge | handed to the stream, not yet handled or dropped |
| `surefold.sqs.consumer.deleted`, `.delete.failures` | counter | `DeleteMessageBatch`, failures per entry and per call |
| `surefold.sqs.consumer.extended`, `.extend.failures` | counter | the visibility renewals |
| `surefold.sqs.consumer.nacks` | counter | `divertLefts(nack = true)` |
| `surefold.sqs.producer.sent`, `.failures` | counter | `send`, `sendAll`, tagged `code` on a refusal |
| `surefold.sqs.producer.send` | histogram, s | call to answer |

## Why this shape

The same names, kinds and tags as `surefold.kafka.*`, so one dashboard reads
both. Latency comes from `SentTimestamp`, which the reader already asks for,
because it measures how stale the work is, which is what an alert is written
on. `redelivered` and `delete.failures` are SQS's version of commit failures:
the at-least-once cost a service is actually paying. The alternative, the
queue's depth from `GetQueueAttributes`, costs a call per reading and needs a
permission. CloudWatch already has it, so it is left out (Open questions 1).

## Stack

- [ ] **`spec-0289-consumer`** — received, bytes, receive, deleted, delete failures, in flight.
      Done when: a test reads each back through `readings(name)` after a run against a fake.
- [ ] **`spec-0289-latency`** — latency, redelivered, extended, extend failures, nacks.
      Done when: on ElasticMQ, a message handled twice records one redelivery and two latencies.
- [ ] **`spec-0289-producer`** — sent, failures by code, send.
      Done when: a batch with one refused entry records one failure tagged with its code.
- [ ] **`spec-0289-docs`** — the table in `docs/observability.md` and `docs/sqs.md`.
      Done when: `AgentDocsSuite` and the guide suites pass.

## Acceptance

```bash
./gradlew :surefold-sqs:build
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **A queue-depth gauge?** Recommended: not by default. CloudWatch has
   `ApproximateNumberOfMessagesVisible`; an opt-in `depthEvery: Duration?`
   later, if someone needs it in the same backend.
2. **Tag by queue name or URL?** Recommended: name. The URL adds account and
   region, which a service has one of; two queues with the same name in two
   regions would need it, and `metricTagged` covers that.
3. **Latency across clocks.** `SentTimestamp` is SQS's clock and "now" is the
   service's. Recommended: record it anyway, as 0163 does with Kafka's
   timestamps, and say in the docs that skew shows up in it.
4. **Per-entry delete failures by `code` tag?** Recommended: yes. The codes are
   a short, fixed list, and `ReceiptHandleIsInvalid` is the one worth alerting on.
