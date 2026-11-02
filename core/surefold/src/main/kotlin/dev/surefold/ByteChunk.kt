package dev.surefold

import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.Objects

/**
 * Bytes nobody can change (spec 0249). Every way in and every way out copies; only [slice] shares the array, which
 * is safe because nothing can write to it once it is inside.
 */
class ByteChunk private constructor(private val array: ByteArray, private val offset: Int, val size: Int) {

    fun isEmpty(): Boolean = size == 0

    operator fun get(index: Int): Byte = array[offset + Objects.checkIndex(index, size)]

    fun slice(from: Int, until: Int): ByteChunk {
        Objects.checkFromToIndex(from, until, size)
        return ByteChunk(array, offset + from, until - from)
    }

    operator fun plus(other: ByteChunk): ByteChunk = when {
        other.isEmpty() -> this

        isEmpty() -> other

        else -> {
            val joined = ByteArray(size + other.size)
            array.copyInto(joined, 0, offset, offset + size)
            other.array.copyInto(joined, size, other.offset, other.offset + other.size)
            ByteChunk(joined, 0, joined.size)
        }
    }

    fun toByteArray(): ByteArray = array.copyOfRange(offset, offset + size)

    /** The same bytes, without a copy: a buffer that cannot write is as safe to hand out as the chunk. */
    fun asReadOnlyBuffer(): ByteBuffer = ByteBuffer.wrap(array, offset, size).slice().asReadOnlyBuffer()

    fun decodeToString(): String = array.decodeToString(offset, offset + size)

    fun writeTo(out: OutputStream) = out.write(array, offset, size)

    override fun equals(other: Any?): Boolean =
        other is ByteChunk &&
            Arrays.equals(array, offset, offset + size, other.array, other.offset, other.offset + other.size)

    override fun hashCode(): Int = ByteBuffer.wrap(array, offset, size).hashCode()

    override fun toString(): String = "ByteChunk($size bytes)"

    companion object {
        private val EMPTY = ByteChunk(ByteArray(0), 0, 0)

        fun empty(): ByteChunk = EMPTY

        /** Copies even here: a Java caller passes its own array as the varargs. */
        fun of(vararg bytes: Byte): ByteChunk = from(bytes)

        fun from(array: ByteArray): ByteChunk = from(array, 0, array.size)

        fun from(array: ByteArray, from: Int, until: Int): ByteChunk {
            Objects.checkFromToIndex(from, until, array.size)
            return ByteChunk(array.copyOfRange(from, until), 0, until - from)
        }

        /** What remains in [buffer], read through a duplicate so its position does not move. */
        fun from(buffer: ByteBuffer): ByteChunk {
            val copy = ByteArray(buffer.remaining())
            buffer.duplicate().get(copy)
            return ByteChunk(copy, 0, copy.size)
        }

        fun from(text: String): ByteChunk = text.encodeToByteArray().let { ByteChunk(it, 0, it.size) }
    }
}
