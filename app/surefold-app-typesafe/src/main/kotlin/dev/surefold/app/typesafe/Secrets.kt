package dev.surefold.app.typesafe

/** What a redacted value is printed as. Fixed width, so the length of a secret is not in the output. */
const val MASK: String = "●●●●●●"

/**
 * A value that prints as [MASK] wherever it is printed.
 *
 * The report is not the only thing that can leak a password: a log line, a data class's `toString`
 * and an exception message all reach it by the same route, and none of them can be reviewed once.
 * So the mask is on the value rather than on the one place that was remembered.
 */
class Secret(private val value: String) {

    /** The value itself, named so that a reader of the call site can see the leak if there is one. */
    fun reveal(): String = value

    override fun toString(): String = MASK

    override fun equals(other: Any?): Boolean = other is Secret && other.value == value

    override fun hashCode(): Int = value.hashCode()
}

/**
 * The paths a report will not print the value of.
 *
 * A name match is the floor: it catches `db.password` and misses
 * `jdbc:postgresql://user:pass@host`, which is the shape a secret usually takes in a configuration.
 * [and] is how the exact answer is given, and there is no way to take one away — a caller who could
 * shrink the default would shrink it on the day the report was wanted most.
 */
class Secrets private constructor(private val declared: Set<String>) {

    /** Whether the value at [path] is printed. The last segment is what is matched, not the whole. */
    fun redacts(path: String): Boolean = path in declared || path.substringAfterLast('.').lowercase() in TELLING

    /** The same, and [paths] besides. */
    fun and(vararg paths: String): Secrets = Secrets(declared + paths)

    companion object {
        /**
         * Names that say what they hold. Matched whole against a path's last segment, so `keystoreType`
         * is not a key and `passwordPolicy` is not a password — a substring match would redact both and
         * teach the reader to stop believing the mask.
         */
        private val TELLING = setOf(
            "password",
            "passphrase",
            "secret",
            "secretkey",
            "token",
            "apitoken",
            "accesstoken",
            "key",
            "apikey",
            "accesskey",
            "privatekey",
            "credential",
            "credentials",
        )

        val default: Secrets = Secrets(emptySet())
    }
}
