@file:OptIn(FailureSeam::class)

package dev.surefold.assert

/** Asserts this has no elements, and returns it. */
fun <T, C : Iterable<T>> C.shouldBeEmpty(): C = also { notEmpty()?.let(::thrown) }

/** Asserts this has an element, and returns it. */
fun <T, C : Iterable<T>> C.shouldNotBeEmpty(): C = also { empty()?.let(::thrown) }

/** Asserts this has [size] elements, and returns it. */
infix fun <T, C : Iterable<T>> C.shouldHaveSize(size: Int): C = also { notOfSize(size)?.let(::thrown) }

/** Asserts this has [element] among its elements, and returns it. */
infix fun <T, C : Iterable<T>> C.shouldContain(element: T): C = also { missing(element)?.let(::thrown) }

/** Asserts this does not have [element] among its elements, and returns it. */
infix fun <T, C : Iterable<T>> C.shouldNotContain(element: T): C = also { holding(element)?.let(::thrown) }

/** Asserts this has every one of [elements], in any order and among others, and returns it. */
infix fun <T, C : Iterable<T>> C.shouldContainAll(elements: Iterable<T>): C =
    also { missingAny(elements)?.let(::thrown) }

/** Asserts this has every one of [elements], in any order and among others, and returns it. */
fun <T, C : Iterable<T>> C.shouldContainAll(vararg elements: T): C = shouldContainAll(elements.asList())

/** Asserts this has exactly [elements], in their order, and returns it. An IDE shows the two lists side by side. */
infix fun <T, C : Iterable<T>> C.shouldContainExactly(elements: Iterable<T>): C =
    also { notExactly(elements)?.let(::thrown) }

/** Asserts this has exactly [elements], in their order, and returns it. */
fun <T, C : Iterable<T>> C.shouldContainExactly(vararg elements: T): C = shouldContainExactly(elements.asList())

/** Asserts this has exactly [elements], each as many times, in any order, and returns it. */
infix fun <T, C : Iterable<T>> C.shouldContainExactlyInAnyOrder(elements: Iterable<T>): C =
    also { missingInAnyOrder(elements)?.let(::thrown) }

/** Asserts this has exactly [elements], each as many times, in any order, and returns it. */
fun <T, C : Iterable<T>> C.shouldContainExactlyInAnyOrder(vararg elements: T): C =
    shouldContainExactlyInAnyOrder(elements.asList())

/** Asserts every element of this is one of [elements], and each of those is in this, and returns it. */
infix fun <T, C : Iterable<T>> C.shouldContainOnly(elements: Iterable<T>): C =
    also { notOnly(elements)?.let(::thrown) }
