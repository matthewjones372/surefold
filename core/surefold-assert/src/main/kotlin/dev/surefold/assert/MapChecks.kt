package dev.surefold.assert

// The checks behind the map matchers: each answers what went wrong, or null when nothing did.

/** This has an entry where none was wanted. */
@FailureSeam fun Map<*, *>.notEmpty(): Miss? = if (isEmpty()) null else Miss(null, this, "no entries")

/** This has no entry. */
@FailureSeam fun Map<*, *>.empty(): Miss? = if (isNotEmpty()) null else Miss(null, this, "at least one entry")

/** This has no entry for [key]. */
@FailureSeam
fun <K> Map<K, *>.missingKey(key: K): Miss? = if (key in this) null else Miss(null, this, "a map with the key $key")

/** This has an entry for [key]. */
@FailureSeam
fun <K> Map<K, *>.holdingKey(key: K): Miss? = if (key !in this) null else Miss(null, this, "a map without the key $key")

/** This does not map [entry]'s key to its value. */
@FailureSeam
fun <K, V> Map<K, V>.missing(entry: Pair<K, V>): Miss? =
    if (holds(entry.first, entry.second)) null else Miss(null, this, "a map with ${entry.first}=${entry.second}")

/** Some entries of [entries] are not in this: those are named. */
@FailureSeam
fun <K, V> Map<K, V>.missingAny(entries: Map<K, V>): Miss? {
    val missing = entries.filterNot { (key, value) -> holds(key, value) }
    return if (missing.isEmpty()) null else Miss(null, "$this, missing $missing", "all of $entries")
}

/** This does not have exactly the entries of [entries]: both maps are carried for an IDE's diff. */
@FailureSeam
fun <K, V> Map<K, V>.notExactly(entries: Map<K, V>): Miss? = if (this == entries) null else Miss(entries, this)

// A key mapped to null is told from a missing key.
private fun <K, V> Map<K, V>.holds(key: K, value: V): Boolean = containsKey(key) && get(key) == value
