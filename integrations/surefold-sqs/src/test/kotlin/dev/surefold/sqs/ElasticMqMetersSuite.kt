package dev.surefold.sqs

import dev.surefold.capturingMetrics
import dev.surefold.fail.Either
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeGreaterThan
import dev.surefold.junit.withClue
import dev.surefold.stream.Exit
import dev.surefold.stream.Forks
import dev.surefold.stream.run
import dev.surefold.stream.take
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** The meters only SQS's own protocol can drive: receive counts, sent times and renewals, on ElasticMQ. */
object ElasticMqMetersSuite : SharedSuite<SqsQueues>() {
    override val shared = sqsQueues()

    private const val SLOW_MILLIS = 2500L

    init {
        test("a message read again records a redelivery, and its latency runs from when it was sent") { sqs ->
            val orders = sqs.create("metered-again", visibility = 1.seconds)
            sqs.send(orders, "a")

            val captured = capturingMetrics { captured ->
                // Dropped on its first receive, so it comes back once its second lapses, and is handled then.
                Sqs.consume(sqs.client, orders, waitTime = 1.seconds, visibility = 1.seconds, extendVisibility = false)
                    .mapMessage { m ->
                        if (m.attributesAsStrings()["ApproximateReceiveCount"] ==
                            "1"
                        ) Either.Left(m) else Either.Right(m)
                    }
                    .divertLefts { }
                    .take(1)
                    .runDeleting()
                    .run(Forks())
                    .toCompletableFuture().get(10, TimeUnit.SECONDS) shouldBe Exit.Done(1L)
                captured
            }

            captured.counter("surefold.sqs.consumer.redelivered") shouldBe 1.0
            withClue("sent, hidden for a second, then handled: at least that long") {
                captured.histogram("surefold.sqs.consumer.latency").single() shouldBeGreaterThan 1.0
            }
        }

        test("a slow handler's message is renewed while it is handled") { sqs ->
            val orders = sqs.create("metered-slow", visibility = 1.seconds)
            sqs.send(orders, "slow")

            val captured = capturingMetrics { captured ->
                Sqs.consume(sqs.client, orders, waitTime = 1.seconds, visibility = 1.seconds)
                    .mapParMessage(2) { Thread.sleep(SLOW_MILLIS) }
                    .take(1)
                    .runDeleting()
                    .run(Forks())
                    .toCompletableFuture().get(10, TimeUnit.SECONDS) shouldBe Exit.Done(1L)
                captured
            }

            captured.counter("surefold.sqs.consumer.extended") shouldBeGreaterThan 1.0
            captured.counter("surefold.sqs.consumer.extend.failures") shouldBe 0.0
        }

        test("a nack is counted") { sqs ->
            val orders = sqs.create("metered-nack")
            sqs.send(orders, "bad", "ok")

            val captured = capturingMetrics { captured ->
                Sqs.consume(sqs.client, orders, waitTime = 1.seconds)
                    .mapMessage { m -> if (m.body() == "bad") Either.Left(m.body()) else Either.Right(m.body()) }
                    .divertLefts(nack = true) { }
                    .take(1)
                    .runDeleting()
                    .run(Forks())
                    .toCompletableFuture().get(10, TimeUnit.SECONDS) shouldBe Exit.Done(1L)
                captured
            }

            (captured.counter("surefold.sqs.consumer.nacks") >= 1.0) shouldBe true
        }
    }
}
