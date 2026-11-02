package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldNotBe
import dev.surefold.assert.shouldThrow
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ReadOnlyBufferException

/** Bytes nobody can change: every way in and out copies, and only a slice shares. */
class ByteChunkTest {

    private fun ByteChunk.bytes(): List<Byte> = toByteArray().toList()

    @Test
    fun `of keeps the bytes it was given, in order`() {
        ByteChunk.of(1, 2, 3).bytes() shouldBe listOf<Byte>(1, 2, 3)
    }

    @Test
    fun `of copies an array spread into it, so changing the array later changes nothing`() {
        val array = byteArrayOf(1, 2, 3)
        val chunk = ByteChunk.of(*array)
        array[0] = 9
        chunk.bytes() shouldBe listOf<Byte>(1, 2, 3)
    }

    @Test
    fun `from an array copies it, so changing the array later changes nothing`() {
        val array = byteArrayOf(1, 2, 3)
        val chunk = ByteChunk.from(array)
        array[0] = 9
        chunk.bytes() shouldBe listOf<Byte>(1, 2, 3)
    }

    @Test
    fun `from part of an array copies that part`() {
        val array = byteArrayOf(0, 1, 2, 3, 4)
        val chunk = ByteChunk.from(array, from = 1, until = 4)
        array[2] = 9
        chunk.bytes() shouldBe listOf<Byte>(1, 2, 3)
    }

    @Test
    fun `from part of an array refuses a range outside it`() {
        shouldThrow<IndexOutOfBoundsException> { ByteChunk.from(byteArrayOf(1, 2), from = 1, until = 3) }
        shouldThrow<IndexOutOfBoundsException> { ByteChunk.from(byteArrayOf(1, 2), from = 2, until = 1) }
    }

    @Test
    fun `from a buffer copies what remains and leaves its position where it was`() {
        val buffer = ByteBuffer.wrap(byteArrayOf(0, 1, 2, 3))
        buffer.position(1)
        val chunk = ByteChunk.from(buffer)
        buffer.put(1, 9)
        chunk.bytes() shouldBe listOf<Byte>(1, 2, 3)
        buffer.position() shouldBe 1
    }

    @Test
    fun `from a string is its UTF-8`() {
        ByteChunk.from("héllo").bytes() shouldBe "héllo".encodeToByteArray().toList()
        ByteChunk.from("héllo").size shouldBe 6
    }

    @Test
    fun `toByteArray is a copy, so changing it changes nothing`() {
        val chunk = ByteChunk.of(1, 2, 3)
        chunk.toByteArray()[0] = 9
        chunk[0] shouldBe 1.toByte()
    }

    @Test
    fun `empty has no bytes`() {
        ByteChunk.empty().size shouldBe 0
        ByteChunk.empty().isEmpty() shouldBe true
        ByteChunk.of(1).isEmpty() shouldBe false
    }

    @Test
    fun `get reads within the chunk and refuses outside it`() {
        val chunk = ByteChunk.of(0, 1, 2, 3).slice(1, 3)
        chunk[0] shouldBe 1.toByte()
        chunk[1] shouldBe 2.toByte()
        shouldThrow<IndexOutOfBoundsException> { chunk[2] }
        shouldThrow<IndexOutOfBoundsException> { chunk[-1] }
    }

    @Test
    fun `a slice is the bytes in its range, and a slice of a slice counts from its own start`() {
        val chunk = ByteChunk.of(0, 1, 2, 3, 4, 5)
        chunk.slice(1, 5).bytes() shouldBe listOf<Byte>(1, 2, 3, 4)
        chunk.slice(1, 5).slice(1, 3).bytes() shouldBe listOf<Byte>(2, 3)
        chunk.slice(2, 2).size shouldBe 0
    }

    @Test
    fun `a slice refuses a range outside the chunk`() {
        val slice = ByteChunk.of(0, 1, 2, 3).slice(1, 3)
        shouldThrow<IndexOutOfBoundsException> { slice.slice(0, 3) }
        shouldThrow<IndexOutOfBoundsException> { slice.slice(2, 1) }
    }

    @Test
    fun `a slice shares the array rather than copying it`() {
        val chunk = ByteChunk.of(0, 1, 2, 3)
        sharesStorage(chunk, chunk.slice(1, 3)) shouldBe true
    }

    /** Nothing public can tell a view from a copy, so this reads the private array of each. */
    private fun sharesStorage(a: ByteChunk, b: ByteChunk): Boolean {
        val field = ByteChunk::class.java.getDeclaredField("array").apply { isAccessible = true }
        return field.get(a) === field.get(b)
    }

    @Test
    fun `plus is both chunks, one after the other`() {
        (ByteChunk.of(1, 2) + ByteChunk.of(3)).bytes() shouldBe listOf<Byte>(1, 2, 3)
        (ByteChunk.of(0, 1, 2).slice(1, 3) + ByteChunk.of(3, 4).slice(0, 1)).bytes() shouldBe listOf<Byte>(1, 2, 3)
    }

    @Test
    fun `plus an empty chunk is the chunk itself`() {
        val chunk = ByteChunk.of(1, 2)
        (chunk + ByteChunk.empty()) shouldBe chunk
        (ByteChunk.empty() + chunk) shouldBe chunk
    }

    @Test
    fun `chunks with equal contents are equal and hash the same, however they were made`() {
        val made = ByteChunk.of(1, 2, 3)
        val sliced = ByteChunk.of(0, 1, 2, 3, 4).slice(1, 4)
        val joined = ByteChunk.of(1) + ByteChunk.of(2, 3)
        made shouldBe sliced
        made shouldBe joined
        made.hashCode() shouldBe sliced.hashCode()
        made.hashCode() shouldBe joined.hashCode()
    }

    @Test
    fun `chunks with different contents are not equal`() {
        ByteChunk.of(1, 2, 3) shouldNotBe ByteChunk.of(1, 2, 4)
        ByteChunk.of(1, 2, 3) shouldNotBe ByteChunk.of(1, 2)
        (ByteChunk.of(1) as Any) shouldNotBe byteArrayOf(1)
    }

    @Test
    fun `decodeToString reads the chunk as UTF-8`() {
        ByteChunk.from("xhéllo").slice(1, 7).decodeToString() shouldBe "héllo"
    }

    @Test
    fun `writeTo writes exactly the chunk's bytes`() {
        val out = ByteArrayOutputStream()
        ByteChunk.of(0, 1, 2, 3).slice(1, 3).writeTo(out)
        out.toByteArray().toList() shouldBe listOf<Byte>(1, 2)
    }

    @Test
    fun `asReadOnlyBuffer reads exactly the chunk and cannot write`() {
        val buffer = ByteChunk.of(0, 1, 2, 3).slice(1, 3).asReadOnlyBuffer()
        buffer.remaining() shouldBe 2
        buffer.get(0) shouldBe 1.toByte()
        buffer.get(1) shouldBe 2.toByte()
        buffer.isReadOnly shouldBe true
        shouldThrow<ReadOnlyBufferException> { buffer.put(0, 9) }
    }

    @Test
    fun `toString gives the size, not the contents`() {
        ByteChunk.of(1, 2, 3).toString() shouldBe "ByteChunk(3 bytes)"
    }
}
