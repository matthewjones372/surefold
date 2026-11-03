# 0287 — A message published to SNS

## Problem

A service that fans events out through SNS publishes with the bare SDK. It has no
sink that passes an element on once SNS has it, no batching, and a refused
message is an exception it catches by hand. `surefold-kafka` has all three for a
topic; SNS has none.

## Not doing

- **No consumer.** SNS pushes; it is read through an SQS subscription with
  spec 0286's `Sqs.consume`. HTTP and Lambda endpoints are out.
- **No subscriptions or topic admin.** The IaC owns them.
- **No meters, no test fake module, no Pekko variant.** Later specs, as for SQS.

## Shape

New module `surefold-sns` in `integrations/`: `surefold-stream` and the AWS SDK
2.55.6 `sns` on its URL-connection client.

```kotlin
val topic = SnsTopic(arn)
val publisher = SnsPublisher(client)                    // AutoCloseable if it opened the client

publisher.publish(topic.message(order.toJson()))       // Either<PublishFailed, Published>

orders
    .publishTo(publisher, topic) { order -> topic.message(order.toJson(), attributes = order.attrs()) }
    .runDrain()

received                                                // Stream<E, Received<A>> from Sqs.consume
    .publishMessageBatch(publisher) { a -> topic.message(a.toJson()) }   // Received<Either<PublishFailed, A>>
```

- `publishTo` is `mapAsync(inFlight)` over `Publish`, as Kafka's is; a refusal
  is a defect, for `restartOnDefect`.
- `publishBatch` groups up to 10 within a duration into one `PublishBatch`, and
  answers per element: SNS reports success and failure per entry, so a refused
  entry is a `Left` for its own element alone, not the rest of the batch.
- FIFO topics take `groupId` and `dedupId` on `message`, refused at build time on
  a standard topic's ARN (it does not end in `.fifo`).
- Message attributes go in as `Map<String, String>`; binary and number types are
  a later addition if anyone asks.

## Why this shape

The Kafka sink's names and outcomes, so a service moving between the two edits
the client, not the pipeline. Depending on `surefold-sqs` only for
`publishMessageBatch` over `Received` (named apart from `publishBatch`, whose
overload Kotlin cannot tell from it) keeps a fan-out from SQS to SNS settling each
message after its publish. The alternative, `surefold-sns` on `surefold-stream`
alone with no `Received` overload, is smaller but leaves that wiring to callers.
Recommended: depend on `surefold-sqs`, see Open questions 1.

## Stack

- [ ] **`spec-0287-module`** — the module, `SnsTopic`, `SnsPublisher.publish`, `publishTo`.
      Done when: sends against a fake `SnsClient` pass, `NoOtherDependenciesTest` holds.
- [ ] **`spec-0287-batch`** — `publishBatch`, per-entry outcomes, FIFO ids.
      Done when: one refused entry fails only its own element.
- [ ] **`spec-0287-guide`** — `docs/sns.md`, modules table, llms.txt, SKILL.md.
      Done when: the guide's fences compile and `AgentDocsSuite` passes.

## Acceptance

```bash
./gradlew :surefold-sns:build
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Depend on `surefold-sqs`?** Answered: yes, for the `Received`
   overloads. Otherwise `surefold-sns` stands alone and the overloads move to a
   third module.
2. **Unwrap the SNS envelope** on an SQS subscription without raw delivery?
   Answered: no. The guide says to turn raw message delivery on.
3. **Share `PublishFailed`/`Published` with Kafka?** Answered: no. SNS has
   its own types now, and they merge with spec 0286's question 1.
4. **One module `surefold-aws-messaging` for both?** Answered: no. One SDK
   per module, as AGENTS.md says.
