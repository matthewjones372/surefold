# 0288 — A queue for your own tests

## Problem

`surefold-sqs` (0286) is tested against fake `SqsClient`s that model only what
each suite needs: one hides messages for a visibility timeout, another counts
deletes. Nothing checks it against SQS's real protocol, such as receipt handles
that change on each receive, redrive after `maxReceiveCount` or the FIFO rules.
A service using `surefold-sqs` has no queue to test against either, and writes
its own fake or starts LocalStack in Docker. `surefold-kafka-test` (0161) solved
the same problem for Kafka with a broker in the test JVM.

## Not doing

- **No Docker, no LocalStack.** As 0161: the point is a queue in the test JVM.
- **No SNS.** ElasticMQ has no SNS. A fake topic for `surefold-sns` is its own
  spec, if anyone asks for one.
- **The fakes stay.** A fake client is still how a suite injects an SDK failure;
  ElasticMQ cannot be told to fail one call.

## Shape

New module `surefold-sqs-test` in `integrations/`: `surefold-sqs`, ElasticMQ's
`elasticmq-rest-sqs_2.13` (1.7.1) and JUnit's Jupiter API, as
`surefold-kafka-test` adds embedded-kafka and Jupiter.

```kotlin
object OrdersSuite : SharedSuite<SqsQueues>() {
    override val shared = sqsQueues()                     // surefold-junit Resource: started once, stopped after

    init {
        test("an order is placed once") { sqs ->
            val orders = sqs.create("orders", visibility = 2.seconds, deadLetters = sqs.create("orders-dlq"))
            sqs.send(orders, "o-1")
            Sqs.consume(sqs.client, orders).take(1).runDeleting().run(Forks()).toCompletableFuture().join()
            sqs.depth(orders) shouldBe Depth(visible = 0, inFlight = 0)
        }
    }
}
```

- `SqsQueues` starts ElasticMQ on a free port and hands out `client`, an
  `SqsClient` pointed at it with static test credentials, which it closes on
  `stop`.
- `create(name, fifo, visibility, deadLetters, maxReceives)` answers a `Queue`
  and sets the redrive policy when `deadLetters` is given. `send`, `receive`
  (which does not delete) and `depth` (visible and in flight, from
  `GetQueueAttributes`) are what a test asserts with.
- A Jupiter class registers `SqsQueues()` with `@RegisterExtension`, as with
  `KafkaBroker`.

## Why this shape

It matches `surefold-kafka-test`, so a service using both learns one pattern.
The alternative, LocalStack through Testcontainers, has SNS too and is closer to
AWS, but needs Docker on every machine that runs the tests. Recommended:
ElasticMQ, with LocalStack left to services that already run Docker.

## Stack

- [ ] **`spec-0288-module`** — the module, `SqsQueues`, `sqsQueues()`, the extension and the helpers.
      Done when: its own suite creates, sends, receives and redrives against ElasticMQ, and `NoOtherDependenciesTest` holds.
- [ ] **`spec-0288-sqs-suites`** — `surefold-sqs` runs its delete, visibility and redrive claims against ElasticMQ too.
      Done when: `ConsumeSuite` and `VisibilitySuite` pass on ElasticMQ as well as the fakes.
- [ ] **`spec-0288-guide`** — "Testing against a queue" in `docs/sqs.md`, the modules table, llms.txt, SKILL.md.
      Done when: the guide's fence compiles and `AgentDocsSuite` passes.

## Acceptance

```bash
./gradlew :surefold-sqs-test:build :surefold-sqs:build
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Pekko versions.** ElasticMQ 1.7.1 brings Pekko 1.5.0 and pekko-http 1.3.0,
   while the repo pins Pekko 1.2.1 for `surefold-pekko`. A service whose tests
   use both would resolve to 1.5.0. Recommended: accept it for a test-scope
   module, and add a test that `surefold-pekko`'s suites pass on 1.5.0 if a
   conflict shows up.
2. **Port.** A free port per `SqsQueues`, or ElasticMQ's fixed 9324?
   Recommended: free port, as `KafkaBroker` does, so suites can run in parallel.
3. **Name.** `SqsQueues` or `ElasticMq`? Recommended: `SqsQueues`, naming what a
   test gets rather than how it is provided, so the backing could change.
4. **Move the fakes here** for services to inject failures with? Recommended:
   not yet. The fakes are small, and each suite's fake fits its own claim.
