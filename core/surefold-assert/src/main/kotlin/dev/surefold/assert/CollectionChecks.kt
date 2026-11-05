package dev.surefold.assert

// The checks behind the collection matchers: each answers what went wrong, or null when nothing did.

/** This has an element where none was wanted. */
@FailureSeam fun Iterable<*>.notEmpty(): Miss? = if (none()) null else Miss(null, toList(), "no elements")

/** This has no element. */
@FailureSeam fun Iterable<*>.empty(): Miss? = if (any()) null else Miss(null, toList(), "at least one element")

/** This does not have [size] elements: the count found is shown with the elements. */
@FailureSeam
fun Iterable<*>.notOfSize(size: Int): Miss? {
    val found = count()
    return if (found == size) null else Miss(null, "$found in ${toList()}", "$size elements")
}

/** [element] is not among this collection's elements. */
@FailureSeam
fun <T> Iterable<T>.missing(element: T): Miss? =
    if (element in this) null else Miss(null, toList(), "a collection containing $element")

/** [element] is among this collection's elements. */
@FailureSeam
fun <T> Iterable<T>.holding(element: T): Miss? =
    if (element !in this) null else Miss(null, toList(), "a collection without $element")

/** Some of [elements] are not in this: those are named. */
@FailureSeam
fun <T> Iterable<T>.missingAny(elements: Iterable<T>): Miss? {
    val missing = elements.filterNot { it in this }
    return if (missing.isEmpty()) null else Miss(null, "${toList()}, missing $missing", "all of ${elements.toList()}")
}

/** This does not have exactly [elements] in their order: both lists are carried for an IDE's diff. */
@FailureSeam
fun <T> Iterable<T>.notExactly(elements: Iterable<T>): Miss? =
    if (toList() == elements.toList()) null else Miss(elements.toList(), toList())

/** This does not have exactly [elements], each as many times, in any order. */
@FailureSeam
fun <T> Iterable<T>.missingInAnyOrder(elements: Iterable<T>): Miss? =
    if (counted(this) == counted(elements)) null else Miss(null, toList(), "${elements.toList()} in any order")

/** This has an element not among [elements], or lacks one of them. */
@FailureSeam
fun <T> Iterable<T>.notOnly(elements: Iterable<T>): Miss? =
    if (toSet() == elements.toSet()) null else Miss(null, toList(), "only and all of ${elements.toList()}")

private fun <T> counted(elements: Iterable<T>): Map<T, Int> = elements.groupingBy { it }.eachCount()
