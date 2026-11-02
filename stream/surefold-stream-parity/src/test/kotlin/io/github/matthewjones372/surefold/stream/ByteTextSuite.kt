package io.github.matthewjones372.surefold.stream

import dev.surefold.ByteChunk
import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import org.apache.pekko.actor.ActorSystem
import java.util.concurrent.TimeUnit

/** Bytes as text and text as lines, however the input was split on its way in (spec 0249). */
object ByteTextSuite : SharedSuite<ActorSystem>() {
    override val shared: Resource<ActorSystem> = actorSystem("surefold-stream-text-test")

    private const val SETTLE_SECONDS = 10L

    private val backends: List<Backend> = listOf(OnPekko, OnForks, OnTestStreams, OnActors)

    /** One-, two-, three- and four-byte characters, so a split lands inside each kind. */
    private const val TEXT = "a é € 😀 z"

    private val bytes = TEXT.encodeToByteArray()

    /** [whole] cut at [at], every piece kept, empty ones too. */
    private fun ByteArray.cut(vararg at: Int): List<ByteChunk> =
        (listOf(0) + at.toList() + size).zipWithNext { from, until -> ByteChunk.from(this, from, until) }

    private fun <E, R : Any> Run<E, R>.settledOn(backend: StreamBackend): Exit<E, R> =
        run(backend).toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)

    private fun decoded(chunks: List<ByteChunk>, backend: StreamBackend): Exit<Nothing, List<String>> =
        Stream.from(chunks).decodeUtf8().runCollect().settledOn(backend)

    private fun lined(pieces: List<String>, backend: StreamBackend): Exit<Nothing, List<String>> =
        Stream.from(pieces).lines().runCollect().settledOn(backend)

    private fun Exit<Nothing, List<String>>.joined(): String = (this as Exit.Done<List<String>>).value.joinToString("")

    init {
        backends.forEach { backend ->
            test("text split in two at every byte decodes to the same text [${backend.name}]") {
                val on = backend.on(it)
                (0..bytes.size).forEach { at ->
                    withClue("split at byte $at") { decoded(bytes.cut(at), on).joined() shouldBe TEXT }
                }
            }

            test("text a byte at a time decodes to the same text [${backend.name}]") {
                val one = (1 until bytes.size).toList().toIntArray()
                decoded(bytes.cut(*one), backend.on(it)).joined() shouldBe TEXT
            }

            test("no element decodes to an empty string [${backend.name}]") {
                val one = (1 until bytes.size).toList().toIntArray()
                val exit = decoded(bytes.cut(*one), backend.on(it)) as Exit.Done<List<String>>
                exit.value.none { s -> s.isEmpty() } shouldBe true
            }

            test("a byte that is not UTF-8 decodes to the replacement character [${backend.name}]") {
                val chunks = listOf(ByteChunk.of(0x61, 0xFF.toByte(), 0x62))
                decoded(chunks, backend.on(it)).joined() shouldBe "a�b"
            }

            test("a character the stream ends inside decodes to the replacement character [${backend.name}]") {
                val euro = "€".encodeToByteArray()
                decoded(listOf(ByteChunk.of(0x61), ByteChunk.from(euro, 0, 2)), backend.on(it)).joined() shouldBe
                    "a�"
            }

            test("encodeUtf8 is UTF-8, one chunk per string [${backend.name}]") {
                val exit = Stream.of("a", "é", "😀").encodeUtf8().runCollect().settledOn(backend.on(it))
                exit shouldBe Exit.Done(listOf(ByteChunk.from("a"), ByteChunk.from("é"), ByteChunk.from("😀")))
            }

            test("lines are the same however the text was split, at \\n and \\r\\n alike [${backend.name}]") {
                val on = backend.on(it)
                val text = "one\r\ntwo\n\nthree\r\nfour"
                val expected = listOf("one", "two", "", "three", "four")
                (0..text.length).forEach { at ->
                    withClue("split at char $at") {
                        lined(listOf(text.substring(0, at), text.substring(at)), on) shouldBe Exit.Done(expected)
                    }
                }
            }

            test("text ending in a newline has no empty line after it, and no text has no lines [${backend.name}]") {
                val on = backend.on(it)
                lined(listOf("a\n", "b\n"), on) shouldBe Exit.Done(listOf("a", "b"))
                lined(emptyList(), on) shouldBe Exit.Done(emptyList())
                lined(listOf(""), on) shouldBe Exit.Done(emptyList())
            }

            test("a carriage return on its own stays in the line [${backend.name}]") {
                lined(listOf("a\rb\n"), backend.on(it)) shouldBe Exit.Done(listOf("a\rb"))
            }
        }
    }
}
