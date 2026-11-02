package dev.surefold.app.kotlinx

/**
 * How a property's name becomes its key. An `@SerialName` is the name the property has, so under
 * [Kebab] it is kept as written unless it has capitals.
 */
enum class Naming {
    /** The property's own name, `arrivalsEvery`. */
    AsDeclared,

    /** `arrivalsEvery` as `arrivals-every`, the way HOCON files are usually written. */
    Kebab,
    ;

    @PublishedApi
    internal fun key(name: String): String = when (this) {
        AsDeclared -> name
        Kebab -> name.replace(WORD_END, "$1-$2").replace(ACRONYM_END, "$1-$2").lowercase()
    }
}

private val WORD_END = Regex("([a-z0-9])([A-Z])")

/** `dbURLHost`: the `L` before `Host` ends the acronym. */
private val ACRONYM_END = Regex("([A-Z])([A-Z][a-z])")
