package io.github.matthewjones372.surefold.stream

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.javadsl.Sink
import org.apache.pekko.stream.javadsl.Source
import org.apache.pekko.stream.testkit.javadsl.TestSink
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger

/** What a declared failure means, asserted through the public API on a real system. */
object StreamSuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-stream-test")

    private data class Row(val id: Int, val customer: String?)

    private data class NoCustomer(val id: Int)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    private val rows = listOf(Row(1, "ada"), Row(2, null), Row(3, "grace"))

    init {
        test("fail in mapOrFail arrives at run as Exit Failed and no later element is processed") { pekko ->
            val seen = AtomicInteger()

            val exit = Stream.from(rows)
                .mapOrFail { row -> row.customer ?: fail(NoCustomer(row.id)) }
                .map { customer ->
                    seen.incrementAndGet()
                    customer
                }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Failed(NoCustomer(2))
            withClue("the row before the failure is the only one that reached the next operator") {
                seen.get() shouldBe 1
            }
        }

        test("a stream that already names a failure can fail again with it") { pekko ->
            val exit = Stream.from(rows)
                .mapOrFail { row -> row.customer ?: fail(NoCustomer(row.id)) }
                .mapOrFail { customer -> if (customer == "ada") fail(NoCustomer(0)) else customer }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Failed(NoCustomer(0))
        }

        test("a throw arrives at run as Exit Died carrying the cause") { pekko ->
            val cause = IllegalStateException("no ledger")

            val exit = Stream.from(rows)
                .map { row -> if (row.id == 2) throw cause else row }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Died(cause)
        }

        test("runFold answers Exit Done with what it folded") { pekko ->
            val exit = Stream.from(listOf(1, 2, 3, 4))
                .filter { n -> n % 2 == 0 }
                .runFold(0) { total, n -> total + n }
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Done(6)
        }

        test("runCollect answers Exit Done with every element in order") { pekko ->
            val exit = Stream.from(listOf("a", "b", "c"))
                .map { s -> s.uppercase() }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Done(listOf("A", "B", "C"))
        }

        test("from an iterable and from a source carry the same elements") { pekko ->
            val fromIterable = Stream.from(listOf(1, 2, 3)).runCollect().run(pekko.system).settled()
            val fromSource = Stream.from(Source.from(listOf(1, 2, 3))).runCollect().run(pekko.system).settled()

            fromIterable shouldBe fromSource
        }

        test("fail on the companion ends the stream with the error it was given") { pekko ->
            val exit = Stream.fail(NoCustomer(7)).runCollect().run(pekko.system).settled()

            exit shouldBe Exit.Failed(NoCustomer(7))
        }

        test("an empty stream is Done with nothing in it") { pekko ->
            val exit = Stream.empty().runCollect().run(pekko.system).settled()

            exit shouldBe Exit.Done(emptyList())
        }

        test("a Run is a description and nothing runs until run is called") { pekko ->
            val seen = AtomicInteger()
            val described = Stream.from(listOf(1, 2, 3))
                .map { n ->
                    seen.incrementAndGet()
                    n
                }
                .runCollect()

            withClue("building the description must not touch a materializer") {
                seen.get() shouldBe 0
            }

            described.run(pekko.system).settled()

            seen.get() shouldBe 3
        }

        test("toSource hands back a source that runs under plain Pekko") { pekko ->
            val source: Source<Int, NotUsed> = Stream.from(listOf(1, 2, 3)).map { n -> n * 2 }.toSource()

            val probe = source.runWith(TestSink.probe(pekko.classic), pekko.system)

            probe.request(3)
            probe.expectNext() shouldBe 2
            probe.expectNext() shouldBe 4
            probe.expectNext() shouldBe 6
            // Pekko's probe checks completion itself, and throws if the stream did not complete, as before.
            probe.expectComplete()
        }

        // The wrapper `fail` travels in is an exception, so a stream that reached
        // plain Pekko carrying one would fail a sink nobody told about it. Only a
        // stream with no declared failure left can get there, and this is that
        // claim run rather than argued.
        test("a source out of toSource completes normally rather than with the failure wrapper") { pekko ->
            val elements = Stream.from(rows)
                .filter { row -> row.customer != null }
                .toSource()
                .runWith(Sink.seq(), pekko.system)
                .toCompletableFuture()
                .join()

            elements shouldBe listOf(Row(1, "ada"), Row(3, "grace"))
        }
    }
}
