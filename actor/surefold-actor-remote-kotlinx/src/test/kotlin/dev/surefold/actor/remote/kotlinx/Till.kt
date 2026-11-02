@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.surefold.actor.remote.kotlinx

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/** Money as a count of pence: a value class, which crosses as the Long it holds. */
@Serializable
@JvmInline
value class Pence(val count: Long)

@Serializable
enum class Reason {
    @ProtoNumber(1)
    Faulty,

    @ProtoNumber(2)
    Unwanted,
}

/** What a till records: a sealed hierarchy of data classes, as a service's domain writes its events. */
@Serializable
sealed interface Till {
    @Serializable
    data class Paid(@ProtoNumber(1) val amount: Pence, @ProtoNumber(2) val reference: String) : Till

    @Serializable
    data class Refunded(@ProtoNumber(1) val amount: Pence, @ProtoNumber(2) val reason: Reason) : Till

    @Serializable
    data object Closed : Till
}

/** A till's state, as a snapshot keeps it. */
@Serializable
data class Takings(
    @ProtoNumber(1) val total: Pence,
    @ProtoNumber(2) val references: List<String>,
    @ProtoNumber(3) val open: Boolean,
)

/** The table every test here writes through: tags that never change. */
val tills: Kotlinx.OneOf<Till> = Kotlinx.oneOf {
    message<Till.Paid>(1)
    message<Till.Refunded>(2)
    message<Till.Closed>(3)
}
