package io.github.matthewjones372.surefold.stream

import dev.surefold.LogLine
import dev.surefold.Logger
import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeInstanceOf
import dev.surefold.assert.shouldContain
import dev.surefold.logger
import org.apache.pekko.stream.javadsl.Flow
import org.apache.pekko.stream.javadsl.Sink
import org.apache.pekko.stream.javadsl.Source
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** A run is checked against the backend it is started on before anything of it materialises. */
class BackendTest {

    /** A backend that owns nothing Pekko built, runs no operator in [declines], and counts what reaches it. */
    private class Elsewhere(private val declines: Set<Class<out Node>> = emptySet()) : StreamBackend {
        val materialised = AtomicInteger()

        @StreamSpi
        override fun runs(node: Node) = node.javaClass !in declines

        @StreamSpi
        override val key = BackendKey("Elsewhere")

        @StreamSpi
        override fun <E, R : Any> materialise(run: Run<E, R>): Running<E, R> {
            materialised.incrementAndGet()
            return object : Running<E, R> {
                override val exit: CompletionStage<Exit<E, R>> = CompletableFuture()

                override fun stop() = Unit

                override fun close() = Unit
            }
        }
    }

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    @Test
    fun `a Pekko source started on another backend dies before any element flows, and names its builder`() {
        val backend = Elsewhere()
        val pulled = AtomicInteger()
        val source = Source.fromIterator { generateSequence { pulled.incrementAndGet() }.iterator() }

        val lines = ConcurrentLinkedQueue<LogLine>()
        val exit = logger.locally(Logger { lines += it }) { Stream.from(source).runCollect().run(backend).settled() }

        val died = exit.shouldBeInstanceOf<Exit.Died>()
        lines.map { it.origin } shouldBe listOf("io.github.matthewjones372.surefold.stream.BackendKt")
        died.cause.message shouldContain "Stream.from, built at BackendTest.kt:"
        died.cause.message shouldContain "holds a Pekko value, and this run was started on Elsewhere"
        pulled.get() shouldBe 0
        backend.materialised.get() shouldBe 0
    }

    @Test
    fun `a Pekko flow spliced in and a Pekko sink are refused by the builder that took them`() {
        val backend = Elsewhere()

        val throughFlow = Stream.of(1, 2).via(Pipe.from(Flow.create<Int>())).runCollect().run(backend).settled()
        val intoSink = Stream.of(1, 2).runWith(Sink.seq()).run(backend).settled()

        throughFlow.shouldBeInstanceOf<Exit.Died>().cause.message shouldContain "Pipe.from, built at BackendTest.kt:"
        intoSink.shouldBeInstanceOf<Exit.Died>().cause.message shouldContain "runWith, built at BackendTest.kt:"
        backend.materialised.get() shouldBe 0
    }

    @Test
    fun `an operator the backend cannot run is refused by name, with the line that wrote it where it has one`() {
        val backend = Elsewhere(declines = setOf(Node.Take::class.java, Node.Map::class.java))

        val taking = Stream.of(1, 2, 3).take(2).runCollect().run(backend).settled()
        val mapping = Stream.of(1, 2, 3).map { it + 1 }.runCollect().run(backend).settled()

        taking.shouldBeInstanceOf<Exit.Died>().cause.message shouldContain "take is not something Elsewhere runs"
        mapping.shouldBeInstanceOf<Exit.Died>().cause.message shouldContain
            "map, built at BackendTest.kt:"
        mapping.shouldBeInstanceOf<Exit.Died>().cause.message shouldContain ", is not something Elsewhere runs"
        backend.materialised.get() shouldBe 0
    }

    @Test
    fun `a description with nothing native in it is handed to the backend it was started on`() {
        val backend = Elsewhere()
        val run = Stream.of(1, 2).map { it + 1 }.filter { it > 2 }.runCollect()

        run.start(backend)

        backend.materialised.get() shouldBe 1
    }
}
