package dev.surefold.assert

// The checks behind the value and Either matchers: each answers what went wrong, or null when nothing did.

/** This does not equal [expected]: the two are carried for an IDE's diff. */
@FailureSeam
fun <A> A.notEqual(expected: A): Miss? = if (this == expected) null else Miss(expected, this)

/** This equals [unexpected]. */
@FailureSeam
fun <A> A.equal(unexpected: A): Miss? =
    if (this != unexpected) null else Miss(null, this, "anything but $unexpected")

/** This is a value where null was wanted. */
@FailureSeam
fun Any?.notNull(): Miss? = if (this == null) null else Miss(null, this)

/** Null, where a value was wanted. */
@FailureSeam
fun wasNull(): Miss = Miss(null, null, "a value, not null")

/** This is not a [T]: the value is shown with its type. */
@FailureSeam
inline fun <reified T> Any?.notInstanceOf(): Miss? =
    if (this is T) null else Miss(null, typed(this), "an instance of ${T::class.qualifiedName}")

/** This is not the very object [expected] is. */
@FailureSeam
fun <A> A.notSameAs(expected: A): Miss? =
    if (this === expected) null else Miss(null, identified(this), identified(expected))

/** This is the very object [unexpected] is. */
@FailureSeam
fun <A> A.sameAs(unexpected: A): Miss? =
    if (this !== unexpected) null else Miss(null, identified(this), "anything but ${identified(unexpected)}")

/** A block threw [thrown], or nothing, where [wanted] was: what it threw is the cause. */
@FailureSeam
fun notThrown(wanted: String, thrown: Throwable?): Miss = Miss(null, thrown ?: "nothing thrown", wanted, thrown)

/** A value with its type, for a check about its type. */
@PublishedApi
internal fun typed(value: Any?): String = value?.let { "$it of ${it::class.qualifiedName}" } ?: "null"

/** A value with its identity, for a check that two are or are not the same object. */
private fun identified(value: Any?): String =
    value?.let { "$it @${Integer.toHexString(System.identityHashCode(it))}" } ?: "null"
