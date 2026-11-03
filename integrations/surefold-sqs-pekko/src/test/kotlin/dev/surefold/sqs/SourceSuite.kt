package dev.surefold.sqs

import dev.surefold.fail.Either
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.withClue
import dev.surefold.stream.Exit
import dev.surefold.stream.PekkoStreams
import dev.surefold.stream.run
import dev.surefold.stream.start
import dev.surefold.stream.take
import dev.surefold.test.eventually
import org.apache.pekko.Done
import org.apache.pekko.stream.connectors.sqs.SqsSourceSettings
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

private data object Refused

/**
 * [client], keeping the timeout of each visibility change it is asked for: the claim a nack makes, without racing a
 * receive. A proxy rather than delegation, which would leave the SDK interface's default methods unimplemented.
 */
private fun nacking(client: SqsAsyncClient, changes: MutableCollection<Int>): SqsAsyncClient =
    Proxy.newProxyInstance(Proxied::class.java.classLoader, arrayOf(Proxied::class.java)) { _, call, args ->
        (args?.singleOrNull() as? ChangeMessageVisibilityRequest)?.let { changes += it.visibilityTimeout() }
        call.invoke(client, *args.orEmpty())
    } as SqsAsyncClient

private typealias Proxied = SqsAsyncClient

object SourceSuite : SharedSuite<PekkoSqsKit>() {
    override val shared = pekkoSqsKit("surefold-sqs-pekko-source-test")

    private val settings = SqsSourceSettings.create().withWaitTimeSeconds(1).withParallelRequests(2)

    init {
        test("only messages whose elements reached the end are deleted") { kit ->
            val orders = kit.sqs.create("pekko-orders")
            kit.sqs.send(orders, "1", "2", "3", "4")

            val exit = Sqs.source(kit.client, orders, settings)
                .mapMessage { it.body() }
                .mapMessageOrFail { body -> if (body == "3") fail(Refused) else body }
                .runDeleting(kit.client, orders)
                .run(PekkoStreams(kit.system))
                .toCompletableFuture().get(10, TimeUnit.SECONDS)

            exit shouldBe Exit.Failed(Refused)
            eventually(within = 5.seconds) {
                withClue("1 and 2 deleted; 3 failed and 4 never reached the end, so both stay") {
                    kit.sqs.depth(orders) shouldBe Depth(visible = 0, inFlight = 2)
                }
            }
        }

        test("a stop drains: what was handed out is deleted, and what was only buffered comes back") { kit ->
            // Long enough that no buffered message lapses and is received again while its first copy is handled: the
            // connector's ack sink fails the run on a stale receipt handle.
            val orders = kit.sqs.create("pekko-drained", visibility = 5.seconds)
            kit.sqs.send(orders, *(1..20).map { "$it" }.toTypedArray())
            val handled = ConcurrentLinkedQueue<String>()

            val running = Sqs.source(kit.client, orders, settings)
                .mapMessage { m -> m.body().also(handled::add) }
                .runDeleting(kit.client, orders)
                .start(PekkoStreams(kit.system))
            eventually(within = 5.seconds) { (handled.size >= 5) shouldBe true }
            running.stop()

            running.exit.toCompletableFuture().get(10, TimeUnit.SECONDS) shouldBe Exit.Done(Done.getInstance())
            eventually(within = 15.seconds) {
                withClue("handled ones deleted; those received but never handed out back once their timeout lapsed") {
                    kit.sqs.depth(orders) shouldBe Depth(visible = 20 - handled.size, inFlight = 0)
                }
            }
        }

        test("a nacked message is made visible again at once") { kit ->
            val orders = kit.sqs.create("pekko-nacked")
            kit.sqs.send(orders, "bad", "ok")
            val changes = ConcurrentLinkedQueue<Int>()
            val nacking = nacking(kit.client, changes)

            Sqs.source(nacking, orders, settings)
                .mapMessage { m -> if (m.body() == "bad") Either.Left(m.body()) else Either.Right(m.body()) }
                .divertLefts(nack = true) { }
                .take(1)
                .runDeleting(nacking, orders)
                .run(PekkoStreams(kit.system))
                .toCompletableFuture().get(10, TimeUnit.SECONDS) shouldBe Exit.Done(Done.getInstance())

            withClue("the connector may receive it again at once, so the visibility change itself is what is held") {
                changes.distinct() shouldBe listOf(0)
            }
        }

        test("an element from Sqs.source ending on runDeleting() is a defect naming the right one") { kit ->
            val orders = kit.sqs.create("pekko-wrong-end")
            kit.sqs.send(orders, "1")

            val exit = Sqs.source(kit.client, orders, settings).take(1).runDeleting().run(PekkoStreams(kit.system))
                .toCompletableFuture().get(10, TimeUnit.SECONDS)

            exit.shouldBeInstanceOf<Exit.Died> { died ->
                (died.cause.message?.contains("runDeleting(client") == true) shouldBe
                    true
            }
        }

        test("an element from Sqs.consume ending on runDeleting(client, queue) is a defect too") { kit ->
            val orders = kit.sqs.create("pekko-wrong-start")
            kit.sqs.send(orders, "1")

            val exit = Sqs.consume(kit.sqs.client, orders, waitTime = 1.seconds).take(1)
                .runDeleting(kit.client, orders).run(PekkoStreams(kit.system))
                .toCompletableFuture().get(10, TimeUnit.SECONDS)

            exit.shouldBeInstanceOf<Exit.Died>()
        }
    }
}
