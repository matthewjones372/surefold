package dev.surefold.stream

import dev.surefold.ByteChunk
import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeGreaterThan
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withClue
import org.apache.pekko.actor.ActorSystem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Bytes read from an InputStream and written to an OutputStream, on every backend (spec 0249). */
object ByteStreamSuite : SharedSuite<ActorSystem>() {
    override val shared: Resource<ActorSystem> = actorSystem("surefold-stream-bytes-test")

    private const val SETTLE_SECONDS = 10L

    private val backends: List<Backend> = listOf(OnPekko, OnForks, OnTestStreams, OnActors)

    /** A mebibyte that is not all one value, so a chunk out of place shows. */
    private val mebibyte: ByteArray = Random(249).nextBytes(1 shl 20)

    /** Counts its closes, which is all a test needs to know about one. */
    private class Counted(bytes: ByteArray) : InputStream() {
        private val inner = ByteArrayInputStream(bytes)
        val closed = AtomicInteger()

        override fun read(): Int = inner.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)

        override fun close() {
            closed.incrementAndGet()
        }
    }

    /** Gives [first], then blocks until it is closed, as a socket with nothing more to say does. */
    private class Quiet(private val first: ByteArray) : InputStream() {
        private var given = false
        val reading = CountDownLatch(1)
        private val shut = CountDownLatch(1)
        val closed = AtomicInteger()

        override fun read(): Int = error("read in blocks")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!given) {
                given = true
                first.copyInto(b, off)
                return first.size
            }
            reading.countDown()
            shut.await()
            throw IOException("Socket closed")
        }

        override fun close() {
            closed.incrementAndGet()
            shut.countDown()
        }
    }

    /** Gives [first], then fails, as a connection reset does. */
    private class Broken(private val first: ByteArray) : InputStream() {
        private var given = false
        val closed = AtomicInteger()

        override fun read(): Int = error("read in blocks")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (given) throw IOException("Connection reset")
            given = true
            first.copyInto(b, off)
            return first.size
        }

        override fun close() {
            closed.incrementAndGet()
        }
    }

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)

    private fun List<ByteChunk>.joined(): ByteChunk {
        val out = ByteArrayOutputStream()
        forEach { it.writeTo(out) }
        return ByteChunk.from(out.toByteArray())
    }

    init {
        backends.forEach { backend ->
            test("a mebibyte read in chunks that do not divide it comes out whole and in order [${backend.name}]") {
                val input = Counted(mebibyte)

                val exit = Stream.read({ input }, chunkSize = 1000).runCollect().run(backend.on(it)).settled()

                exit.shouldBeInstanceOf<Exit.Done<List<ByteChunk>>> { done ->
                    done.value.joined() shouldBe ByteChunk.from(mebibyte)
                    withClue("no chunk is longer than asked for") { done.value.maxOf { c -> c.size } shouldBe 1000 }
                }
                withClue("the InputStream is closed once the run is Done") { input.closed.get() shouldBe 1 }
            }

            test("runInto writes every byte read, and answers how many [${backend.name}]") {
                val out = ByteArrayOutputStream()

                val exit = Stream.read({ Counted(mebibyte) }).runInto(out).run(backend.on(it)).settled()

                exit shouldBe Exit.Done(mebibyte.size.toLong())
                ByteChunk.from(out.toByteArray()) shouldBe ByteChunk.from(mebibyte)
            }

            test("a read that fails is a defect, what came before it is kept, and it is closed [${backend.name}]") {
                val input = Broken(byteArrayOf(1, 2, 3))
                val out = ByteArrayOutputStream()

                val exit = Stream.read({ input }).runInto(out).run(backend.on(it)).settled()

                exit.shouldBeInstanceOf<Exit.Died> { died ->
                    died.cause.message.orEmpty() shouldContain
                        "Connection reset"
                }
                out.toByteArray().toList() shouldBe listOf<Byte>(1, 2, 3)
                input.closed.get() shouldBe 1
            }
        }

        // TestStreams' start returns only once the run waits on its clock, so a read blocked on anything else blocks
        // the test that started it, and there is nobody left to call stop.
        (backends - OnTestStreams).forEach { backend ->
            test("a stop wakes a read that is blocked, by closing the stream it reads [${backend.name}]") {
                val input = Quiet(byteArrayOf(1, 2, 3))
                val running = Stream.read({ input }).runCollect().start(backend.on(it))
                withClue("the second read was reached") {
                    input.reading.await(SETTLE_SECONDS, TimeUnit.SECONDS) shouldBe
                        true
                }

                running.stop()

                withClue("without the close in wake, the run waits on a read that never returns") {
                    running.exit.settled() shouldBe Exit.Done(listOf(ByteChunk.of(1, 2, 3)))
                }
                input.closed.get() shouldBeGreaterThan 0
            }
        }

        test("a chunk size under one is refused when the stream is described") {
            shouldThrow<IllegalArgumentException>({
                Stream.read({ ByteArrayInputStream(ByteArray(0)) }, chunkSize = 0)
            }) { refused -> refused.message.orEmpty() shouldContain "chunkSize" }
        }
    }
}
