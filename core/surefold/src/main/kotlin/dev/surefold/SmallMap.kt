package dev.surefold

/**
 * An annotated block's pairs as one array of entries in insertion order, for the few pairs a block carries and the
 * backends that only walk them in order (spec 0283). No hash table: a lookup by key is a scan, which at this size is
 * cheap and in any case rare. Equal, by the `Map` contract, to any map holding the same pairs.
 */
internal class SmallMap private constructor(private val pairs: Array<Entry>) : AbstractMap<String, String>() {

    /** Built once with the map, so a line that iterates it allocates nothing more than the iterator. */
    override val entries: Set<Map.Entry<String, String>> = object : AbstractSet<Map.Entry<String, String>>() {
        override val size: Int get() = pairs.size

        override fun iterator(): Iterator<Map.Entry<String, String>> = pairs.iterator()
    }

    override val size: Int get() = pairs.size

    override fun get(key: String): String? = pairs.firstOrNull { it.key == key }?.value

    override fun containsKey(key: String): Boolean = pairs.any { it.key == key }

    /** One pair, equal to any `Map.Entry` with the same key and value, and hashed as the `Map.Entry` contract says. */
    private class Entry(override val key: String, override val value: String) : Map.Entry<String, String> {
        override fun equals(other: Any?): Boolean = other is Map.Entry<*, *> && key == other.key && value == other.value

        override fun hashCode(): Int = key.hashCode() xor value.hashCode()

        override fun toString(): String = "$key=$value"
    }

    companion object {
        /** Above this many pairs a block's map is a `LinkedHashMap`, so a lookup never scans far (spec 0283). */
        const val MOST = 8

        /**
         * [outer]'s pairs, then [pairs]: an inner pair with an outer key keeps the outer key's place and takes the
         * inner value, as `outer + pairs` does.
         */
        fun of(outer: Map<String, String>, pairs: Array<out Pair<String, String>>): Map<String, String> {
            if (outer.size + pairs.size > MOST) return outer + pairs
            // Built straight into an array of the most it can need, then trimmed only if an inner key replaced one.
            val merged = arrayOfNulls<Entry>(outer.size + pairs.size)
            var count = 0
            if (outer is SmallMap) {
                outer.pairs.copyInto(merged)
                count = outer.pairs.size
            } else {
                outer.forEach { (key, value) -> merged[count++] = Entry(key, value) }
            }
            for ((key, value) in pairs) {
                var at = 0
                while (at < count && merged[at]?.key != key) at++
                merged[at] = Entry(key, value)
                if (at == count) count++
            }
            @Suppress("UNCHECKED_CAST") // every slot below count is filled
            val filled = (if (count == merged.size) merged else merged.copyOf(count)) as Array<Entry>
            return SmallMap(filled)
        }
    }
}
