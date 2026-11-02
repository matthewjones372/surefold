package dev.surefold.cache

/**
 * How often each key has been asked for lately, roughly (TinyLFU's count-min sketch, after Einziger, Friedman and
 * Manes, 2017): four rows of small counters, a key's count the least of its four. Counts are halved once [sample]
 * increments have been made, so what was popular an hour ago does not stay popular for ever.
 */
internal class Sketch(capacity: Int) {
    private val width = Integer.highestOneBit(maxOf(capacity, MIN_WIDTH) * 2 - 1).coerceAtMost(MAX_WIDTH)
    private val rows = Array(DEPTH) { IntArray(width) }
    private val sample = width.toLong() * SAMPLE_PER_SLOT
    private var increments = 0L

    /** Counts one more ask for [key]. */
    fun increment(key: Any) {
        val hash = spread(key.hashCode())
        var added = false
        for (row in 0 until DEPTH) {
            val at = index(hash, row)
            if (rows[row][at] < MAX_COUNT) {
                rows[row][at]++
                added = true
            }
        }
        if (added && ++increments >= sample) halve()
    }

    /** About how many times [key] has been asked for lately, at most [MAX_COUNT]. */
    fun frequency(key: Any): Int {
        val hash = spread(key.hashCode())
        return (0 until DEPTH).minOf { row -> rows[row][index(hash, row)] }
    }

    private fun halve() {
        rows.forEach { row -> row.indices.forEach { row[it] = row[it] ushr 1 } }
        increments /= 2
    }

    private fun index(hash: Int, row: Int): Int {
        val mixed = (hash + SEEDS[row]) * SEEDS[row]
        return (mixed xor (mixed ushr SHIFT)) and (width - 1)
    }

    private fun spread(hash: Int): Int = (hash xor (hash ushr SHIFT)) * GOLDEN

    private companion object {
        const val DEPTH = 4
        const val MAX_COUNT = 15
        const val MIN_WIDTH = 16
        const val MAX_WIDTH = 1 shl 22
        const val SAMPLE_PER_SLOT = 10L
        const val SHIFT = 16
        const val GOLDEN = -0x61c88647
        val SEEDS = intArrayOf(-0x3361d2af, -0x7a79e5ed, 0x27d4eb2f, 0x165667b1)
    }
}
