package dev.surefold.app.kotlinx

import dev.surefold.app.typesafe.MASK
import dev.surefold.app.typesafe.Secrets
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializer
import dev.surefold.app.typesafe.Secret as Masked

/** surefold-app-typesafe's `Secret`, with the serializer surefold-app-typesafe cannot carry without kotlinx. */
typealias Secret =
    @Serializable(with = SecretSerializer::class)
    Masked

/** Reads a [Secret] from a string. Writing one writes the mask, so no encoder can leak it. */
object SecretSerializer : KSerializer<Masked> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(SECRET, PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Masked = Masked(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: Masked) = encoder.encodeString(MASK)
}

internal const val SECRET = "dev.surefold.app.typesafe.Secret"

/** The same, and every path under [path] that [A] reads as a [Secret], which a name match cannot see. */
inline fun <reified A : Any> Secrets.andSecretsOf(path: String, naming: Naming = Naming.AsDeclared): Secrets =
    secretPaths(serializer<A>().descriptor, path, naming).fold(this) { secrets, secret -> secrets.and(secret) }

@PublishedApi
internal fun secretPaths(descriptor: SerialDescriptor, at: String, naming: Naming): List<String> =
    (0 until descriptor.elementsCount).flatMap { index ->
        val element = descriptor.getElementDescriptor(index)
        val path = "$at.${naming.key(descriptor.getElementName(index))}"
        when (shapeOf(element)) {
            Shape.Secret -> listOf(path)
            Shape.Section -> secretPaths(element, path, naming)
            Shape.Duration, Shape.Strings, Shape.Unsupported, Shape.Leaf -> emptyList()
        }
    }
