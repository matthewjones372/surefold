@file:OptIn(FailureSeam::class)

package dev.surefold.assert

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/** Asserts this does not equal [unexpected], and returns it. */
infix fun <A> A.shouldNotBe(unexpected: A): A = also { equal(unexpected)?.let(::thrown) }

/** Asserts this is null. */
fun <A> A?.shouldBeNull() {
    notNull()?.let(::thrown)
}

/** Asserts this is not null, and returns it as its non-null type. */
@OptIn(ExperimentalContracts::class)
fun <A : Any> A?.shouldNotBeNull(): A {
    contract { returns() implies (this@shouldNotBeNull != null) }
    return this ?: thrown(wasNull())
}

/** Asserts this is a [T], and returns it as one. */
@OptIn(ExperimentalContracts::class)
inline fun <reified T> Any?.shouldBeInstanceOf(): T {
    contract { returns() implies (this@shouldBeInstanceOf is T) }
    notInstanceOf<T>()?.let(::thrown)
    return this as T
}

/** Asserts this is the very object [expected] is, not only an equal one, and returns it. */
infix fun <A> A.shouldBeSameInstanceAs(expected: A): A = also { notSameAs(expected)?.let(::thrown) }

/** Asserts this is not the very object [unexpected] is, though it may equal it, and returns it. */
infix fun <A> A.shouldNotBeSameInstanceAs(unexpected: A): A = also { sameAs(unexpected)?.let(::thrown) }
