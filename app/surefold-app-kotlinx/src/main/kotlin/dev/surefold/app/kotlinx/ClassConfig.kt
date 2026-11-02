package dev.surefold.app.kotlinx

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import dev.surefold.app.Module
import dev.surefold.app.typesafe.Reading
import dev.surefold.app.typesafe.choosing
import dev.surefold.app.typesafe.config
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer

/**
 * The section at [path] as a node, read from [A]'s own properties: each is a path named after it, a
 * nested class a nested section. Every fault is named at once, as the block form names them.
 */
inline fun <reified A : Any> config(path: String, naming: Naming = Naming.AsDeclared): Module =
    config(path) { decoded(serializer<A>(), naming) }

/** `choosing` with the section read from [A]'s own properties, as [config] reads it. */
inline fun <reified A : Any> Config.choosing(
    path: String,
    naming: Naming = Naming.AsDeclared,
    chosen: (A) -> Module,
): Module = choosing(path, { decoded(serializer<A>(), naming) }, chosen)

@PublishedApi
internal fun <A> Reading.decoded(deserializer: DeserializationStrategy<A>, naming: Naming): A =
    deserializer.deserialize(SectionDecoder(this, naming))

private const val DURATION = "kotlin.time.Duration"
private const val LIST = "kotlin.collections.ArrayList"

/** What a property's type is to a class read. */
internal enum class Shape { Duration, Secret, Strings, Section, Unsupported, Leaf }

@OptIn(ExperimentalSerializationApi::class)
internal fun shapeOf(descriptor: SerialDescriptor): Shape {
    val name = descriptor.serialName.removeSuffix("?")
    return when {
        name == DURATION -> Shape.Duration
        name == SECRET -> Shape.Secret
        name == LIST && descriptor.getElementDescriptor(0).kind == PrimitiveKind.STRING -> Shape.Strings
        descriptor.kind == StructureKind.CLASS -> Shape.Section
        descriptor.kind == StructureKind.LIST || descriptor.kind in UNSUPPORTED -> Shape.Unsupported
        else -> Shape.Leaf
    }
}

private val UNSUPPORTED: Set<SerialKind> = setOf(
    PrimitiveKind.DOUBLE,
    PrimitiveKind.FLOAT,
    PrimitiveKind.SHORT,
    PrimitiveKind.BYTE,
    PrimitiveKind.CHAR,
    StructureKind.MAP,
)

/** `Map<String, Int>` rather than kotlinx's `kotlin.collections.LinkedHashMap`. */
@OptIn(ExperimentalSerializationApi::class)
private fun typeName(descriptor: SerialDescriptor): String = when (descriptor.kind) {
    StructureKind.LIST, StructureKind.MAP -> {
        val name = descriptor.serialName
        val elements = List(descriptor.elementsCount) { typeName(descriptor.getElementDescriptor(it)) }
        name.removeSuffix("?").substringAfterLast('.').removePrefix("Linked").removePrefix("Hash")
            .replace("ArrayList", "List") + elements.joinToString(", ", "<", ">") +
            (if (name.endsWith("?")) "?" else "")
    }

    else -> descriptor.serialName.removePrefix("kotlin.")
}

/**
 * Each property read through [reading]'s own leaves, so a fault is recorded and the read carries on.
 *
 * Every property without a default is handed out, present or not: absent, it is the leaf that records
 * the fault, where kotlinx would stop at the first with a `MissingFieldException`.
 */
@OptIn(ExperimentalSerializationApi::class)
private class SectionDecoder(private val reading: Reading, private val naming: Naming) : AbstractDecoder() {

    override val serializersModule: SerializersModule = EmptySerializersModule()

    private var next = 0
    private var key = ""

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        while (next < descriptor.elementsCount) {
            val index = next++
            key = naming.key(descriptor.getElementName(index))
            val element = descriptor.getElementDescriptor(index)
            if (shapeOf(element) == Shape.Unsupported) {
                reading.of(Unit) { throw ConfigException.BadValue(key, "is a ${typeName(element)}, $UNREAD") }
            }
            if (!descriptor.isElementOptional(index) || reading.config.hasPath(key)) return index
        }
        return CompositeDecoder.DECODE_DONE
    }

    override fun decodeNotNullMark(): Boolean = reading.config.hasPath(key)

    override fun decodeString(): String = reading.string(key)

    override fun decodeInt(): Int = reading.int(key)

    override fun decodeLong(): Long = reading.long(key)

    override fun decodeBoolean(): Boolean = reading.boolean(key)

    // The unsupported leaves: decodeElementIndex has recorded the fault, so the value is discarded.
    override fun decodeDouble(): Double = 0.0

    override fun decodeFloat(): Float = 0f

    override fun decodeShort(): Short = 0

    override fun decodeByte(): Byte = 0

    override fun decodeChar(): Char = ' '

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val entries = List(enumDescriptor.elementsCount, enumDescriptor::getElementName)
        return reading.of(key, 0) {
            val said = getString(key)
            entries.indexOf(said).takeIf { it >= 0 }
                ?: throw ConfigException.BadValue(key, "is ${entries.joinToString(" or ")}, not $said")
        }
    }

    // The casts hold because each branch is chosen by the descriptor of the very type it returns.
    @Suppress("UNCHECKED_CAST")
    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        val descriptor = deserializer.descriptor
        return when (shapeOf(descriptor)) {
            // kotlinx's own serializer reads ISO-8601; HOCON says `30s`.
            Shape.Duration -> reading.duration(key) as T

            Shape.Strings -> reading.strings(key) as T

            Shape.Section -> reading.section(key) { decoded(deserializer, naming) }

            // Through secret() rather than the string leaf, so the path is recorded as one.
            Shape.Secret -> reading.secret(key) as T

            // An empty collection of the declared type, so the read goes on to the next property.
            Shape.Unsupported -> deserializer.deserialize(Empty)

            Shape.Leaf -> super.decodeSerializableValue(deserializer)
        }
    }
}

private const val UNREAD = "which config<A> does not read; read it in the block form"

@OptIn(ExperimentalSerializationApi::class)
private object Empty : AbstractDecoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int = CompositeDecoder.DECODE_DONE
}
