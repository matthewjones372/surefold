@file:OptIn(FailureSeam::class)

package dev.surefold.junit

import dev.surefold.assert.FailureSeam
import dev.surefold.assert.empty
import dev.surefold.assert.holding
import dev.surefold.assert.holdingKey
import dev.surefold.assert.missing
import dev.surefold.assert.missingAny
import dev.surefold.assert.missingInAnyOrder
import dev.surefold.assert.missingKey
import dev.surefold.assert.notEmpty
import dev.surefold.assert.notExactly
import dev.surefold.assert.notOfSize
import dev.surefold.assert.notOnly

// The matchers on a collection or a map, as Matchers.kt's: each records surefold-assert's check.

/** Holds if [element] is among this collection's elements; fails carrying the elements. */
infix fun <T> Iterable<T>.shouldContain(element: T) = verdict(missing(element))

/** Holds if [element] is not among this collection's elements. */
infix fun <T> Iterable<T>.shouldNotContain(element: T) = verdict(holding(element))

/** Holds if this has exactly [elements], each as many times, in any order; fails carrying the elements found. */
infix fun <T> Iterable<T>.shouldContainExactlyInAnyOrder(elements: Iterable<T>) =
    verdict(missingInAnyOrder(elements))

/** Holds if this has exactly [elements], each as many times, in any order. */
fun <T> Iterable<T>.shouldContainExactlyInAnyOrder(vararg elements: T) =
    shouldContainExactlyInAnyOrder(elements.asList())

/** Holds if this has exactly [elements], in their order; fails carrying both lists for an IDE's diff. */
infix fun <T> Iterable<T>.shouldContainExactly(elements: Iterable<T>) = verdict(notExactly(elements))

/** Holds if this has exactly [elements], in their order. */
fun <T> Iterable<T>.shouldContainExactly(vararg elements: T) = shouldContainExactly(elements.asList())

/** Holds if every element of this is one of [elements], and each of those is in this. */
infix fun <T> Iterable<T>.shouldContainOnly(elements: Iterable<T>) = verdict(notOnly(elements))

/** Holds if every element of this is one of [elements], and each of those is in this. */
fun <T> Iterable<T>.shouldContainOnly(vararg elements: T) = shouldContainOnly(elements.asList())

/** Holds if this has exactly the entries of [entries]; fails carrying both maps for an IDE's diff. */
infix fun <K, V> Map<K, V>.shouldContainExactly(entries: Map<K, V>) = verdict(notExactly(entries))

/** Holds if this has [size] elements; fails naming how many it has, and which. */
infix fun Iterable<*>.shouldHaveSize(size: Int) = verdict(notOfSize(size))

/** Holds if this has no elements. */
fun Iterable<*>.shouldBeEmpty() = verdict(notEmpty())

/** Holds if this has an element. */
fun Iterable<*>.shouldNotBeEmpty() = verdict(empty())

/** Holds if this has every one of [elements], in any order and among others; fails naming those missing. */
infix fun <T> Iterable<T>.shouldContainAll(elements: Iterable<T>) = verdict(missingAny(elements))

/** Holds if this has every one of [elements], in any order and among others. */
fun <T> Iterable<T>.shouldContainAll(vararg elements: T) = shouldContainAll(elements.asList())

/** Holds if this has an entry for [key]; fails carrying the map. */
infix fun <K> Map<K, *>.shouldContainKey(key: K) = verdict(missingKey(key))

/** Holds if this has no entry for [key]. */
infix fun <K> Map<K, *>.shouldNotContainKey(key: K) = verdict(holdingKey(key))

/** Holds if this maps [entry]'s key to its value. */
infix fun <K, V> Map<K, V>.shouldContain(entry: Pair<K, V>) = verdict(missing(entry))

/** Holds if this has every entry of [entries], among others; fails naming those missing. */
infix fun <K, V> Map<K, V>.shouldContainAll(entries: Map<K, V>) = verdict(missingAny(entries))

/** Holds if this has no entries. */
fun Map<*, *>.shouldBeEmpty() = verdict(notEmpty())

/** Holds if this has an entry. */
fun Map<*, *>.shouldNotBeEmpty() = verdict(empty())
