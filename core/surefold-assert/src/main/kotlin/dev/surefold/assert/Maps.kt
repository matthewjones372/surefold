@file:OptIn(FailureSeam::class)

package dev.surefold.assert

/** Asserts this has no entries, and returns it. */
fun <K, V, M : Map<K, V>> M.shouldBeEmpty(): M = also { notEmpty()?.let(::thrown) }

/** Asserts this has an entry, and returns it. */
fun <K, V, M : Map<K, V>> M.shouldNotBeEmpty(): M = also { empty()?.let(::thrown) }

/** Asserts this has an entry for [key], and returns it. */
infix fun <K, V, M : Map<K, V>> M.shouldContainKey(key: K): M = also { missingKey(key)?.let(::thrown) }

/** Asserts this has no entry for [key], and returns it. */
infix fun <K, V, M : Map<K, V>> M.shouldNotContainKey(key: K): M = also { holdingKey(key)?.let(::thrown) }

/** Asserts this maps [entry]'s key to its value, and returns it. */
infix fun <K, V, M : Map<K, V>> M.shouldContain(entry: Pair<K, V>): M = also { missing(entry)?.let(::thrown) }

/** Asserts this has every entry of [entries], among others, and returns it. */
infix fun <K, V, M : Map<K, V>> M.shouldContainAll(entries: Map<K, V>): M =
    also { missingAny(entries)?.let(::thrown) }

/** Asserts this has exactly the entries of [entries], and returns it. An IDE shows the two maps side by side. */
infix fun <K, V, M : Map<K, V>> M.shouldContainExactly(entries: Map<K, V>): M =
    also { notExactly(entries)?.let(::thrown) }
