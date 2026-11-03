# 0291 — A topic for your own tests

## Problem

`surefold-sns` (0287) is tested against fake `SnsClient`s, one per suite, that
answer `publish` and `publishBatch` and keep what they were given. A service
using it has nothing to test against: it writes its own fake, or starts
LocalStack in Docker. ElasticMQ, which `surefold-sqs-test` (0288) runs, has no
SNS, so the fan-out a service depends on, a topic delivering to the queues
subscribed to it, is tested nowhere.

## Not doing

- **No Docker, no LocalStack**, as 0288.
- **No SNS wire protocol.** The topic is a fake `SnsClient` in the test JVM; it
  checks what SNS checks that a test can hit (FIFO ids, batch size, attribute
  types), not request signing or serialisation.
- **No HTTP, Lambda, email or SMS subscriptions.** Queues only.

## Shape

New module `surefold-sns-test` in `integrations/`: `surefold-sns` and
`surefold-sqs-test`, and nothing more.

```kotlin
object AnnouncingSuite : SharedSuite<SnsTopics>() {
    override val shared = snsTopics()                      // SqsQueues underneath, started once

    init {
        test("a placed order is announced to every subscriber") { sns ->
            val placed = sns.create("placed")
            val billing = sns.queues.create("billing")
            sns.subscribe(placed, billing)                 // raw delivery by default
            SnsPublisher(sns.client).publish(placed.message("o-1"))
            sns.queues.receive(billing).map { it.body() } shouldBe listOf("o-1")
            sns.published(placed).map { it.body } shouldBe listOf("o-1")
        }
    }
}
```

- `SnsTopics` owns an `SqsQueues`, exposed as `queues`, and a `client`. That is
  an `SnsClient` whose `publish` and `publishBatch` deliver to every subscribed
  queue through ElasticMQ, answering as SNS does: an id per message, a
  per-entry failure for an entry SNS would refuse.
- `create(name, fifo)` answers an `SnsTopic`. `subscribe(topic, queue, raw =
  true)` delivers the body as it was published, or, with `raw = false`, in SNS's
  JSON envelope, so a service can test the setup 0287's guide tells it to avoid.
- `published(topic)` is what was published, kept whether or not anything is
  subscribed.
- A Jupiter class registers `SnsTopics()` with `@RegisterExtension`, as with
  `SqsQueues`.

## Why this shape

A fake topic over real queues tests what a service depends on, delivery to
queues that `Sqs.consume` then reads, without Docker. LocalStack would test the
wire protocol too, but would need Docker on every machine that runs the tests,
which is the reason 0161 and 0288 gave for their choice. Recommended: the fake,
with LocalStack left to services that already run Docker.

## Stack

- [ ] **`spec-0291-module`** — `SnsTopics`, its `SnsClient`, `snsTopics()`, the extension, the helpers.
      Done when: its suite publishes, batches and fans out to two queues, raw and enveloped.
- [ ] **`spec-0291-sns-suites`** — `surefold-sns`'s publish and batch claims, and the SQS-to-SNS guide example, held on it.
      Done when: the guide's `announcing` runs end to end, from one queue through a topic to another.
- [ ] **`spec-0291-guide`** — "Testing against a topic" in `docs/sns.md`, the modules table, llms.txt, SKILL.md.
      Done when: the guide's fence compiles and `AgentDocsSuite` passes.

## Acceptance

```bash
./gradlew :surefold-sns-test:build :surefold-sns:build
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **A fake rather than LocalStack?** Recommended: the fake, as above.
2. **Which SNS refusals to model?** Recommended: the ones a caller can cause:
   FIFO ids on a standard topic and missing on a FIFO one, more than ten in a
   batch, an empty body. Throttling and auth are out.
3. **Envelope shape for `raw = false`.** Recommended: SNS's documented fields
   (`Type`, `MessageId`, `TopicArn`, `Message`, `Timestamp`,
   `MessageAttributes`), with no signature.
4. **Where does `surefold-sns-test` sit?** It needs `surefold-sqs-test`, which
   brings Pekko 1.5 (0288). Recommended: accept that, as 0288 did; it is test
   scope only.
