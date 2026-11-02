package io.github.matthewjones372.surefold.stream

import dev.surefold.ByteChunk
import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import dev.surefold.junit.withClue
import org.apache.pekko.actor.ActorSystem
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Frames written with a 4-byte length before each, and read back however the bytes were split (spec 0249). */
object FrameSuite : SharedSuite<ActorSystem>() {
    override val shared: Resource<ActorSystem> = actorSystem("surefold-stream-frame-test")

    private const val SETTLE_SECONDS = 10L

    private val backends: List<Backend> = listOf(OnPekko, OnForks, OnTestStreams, OnActors)

    /** An empty frame, a short one, and one long enough that its length needs more than one byte. */
    private val frames: List<ByteChunk> =
        listOf(ByteChunk.empty(), ByteChunk.from("hello"), ByteChunk.from(Random(249).nextBytes(300)))

    /** The frames as `DataOutputStream.writeInt` and `write` put them, which is how Wire.kt writes its own. */
    private val written: ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            frames.forEach { frame ->
                out.writeInt(frame.size)
                out.write(frame.toByteArray())
            }
        }
    }.toByteArray()

    private fun <E, R : Any> Run<E, R>.settledOn(backend: StreamBackend): Exit<E, R> =
        run(backend).toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)

    private fun ByteArray.cut(at: Int): List<ByteChunk> =
        listOf(ByteChunk.from(this, 0, at), ByteChunk.from(this, at, size))

    private fun List<ByteChunk>.joined(): ByteArray =
        ByteArrayOutputStream().also { out -> forEach { it.writeTo(out) } }.toByteArray()

    init {
        backends.forEach { backend ->
            test("framed writes what DataOutputStream writes: each frame's length, then the frame [${backend.name}]") {
                val exit = Stream.from(frames).framed().runCollect().settledOn(backend.on(it))

                exit.shouldBeInstanceOf<Exit.Done<List<ByteChunk>>> { done ->
                    ByteChunk.from(done.value.joined()) shouldBe ByteChunk.from(written)
                }
            }

            test("unframed reads the same frames however the bytes were split in two [${backend.name}]") {
                val on = backend.on(it)
                (0..written.size).forEach { at ->
                    withClue("split at byte $at") {
                        Stream.from(written.cut(at)).unframed().runCollect().settledOn(on) shouldBe Exit.Done(frames)
                    }
                }
            }

            test("unframed reads the same frames from a byte at a time [${backend.name}]") {
                val oneByOne = written.indices.map { i -> ByteChunk.from(written, i, i + 1) }

                Stream.from(oneByOne).unframed().runCollect().settledOn(backend.on(it)) shouldBe Exit.Done(frames)
            }

            test("a frame longer than maxSize is a defect, before any of it is read [${backend.name}]") {
                val exit = Stream.from(listOf(ByteChunk.from(written))).unframed(maxSize = 100).runCollect()
                    .settledOn(backend.on(it))

                exit.shouldBeInstanceOf<Exit.Died> { died -> died.cause.message.orEmpty() shouldContain "300 bytes" }
            }

            test("a stream that ends partway through a frame is a defect [${backend.name}]") {
                val short = ByteChunk.from(written, 0, written.size - 1)

                val exit = Stream.from(listOf(short)).unframed().runCollect().settledOn(backend.on(it))

                exit.shouldBeInstanceOf<Exit.Died> { died -> died.cause.message.orEmpty() shouldContain "partway" }
            }

            test("framed then unframed gives back what went in [${backend.name}]") {
                val exit = Stream.from(frames).framed().unframed().runCollect().settledOn(backend.on(it))

                exit shouldBe Exit.Done(frames)
            }
        }
    }
}
