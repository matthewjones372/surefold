package dev.surefold.auth

/**
 * Something that must not be logged, traced or shown: a password, a token, a client secret. Its `toString` is a mask,
 * so a log line or a span attribute that includes one by accident says nothing. [reveal] is for the code that needs it.
 */
class Secret(private val value: String) {
    fun reveal(): String = value

    override fun toString(): String = "Secret(****)"

    override fun equals(other: Any?): Boolean = other is Secret && constantTimeEquals(value, other.value)

    override fun hashCode(): Int = 0
}

/** Whether [a] and [b] are equal, taking as long whatever they hold: no early return on the first difference. */
fun constantTimeEquals(a: String, b: String): Boolean =
    java.security.MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
