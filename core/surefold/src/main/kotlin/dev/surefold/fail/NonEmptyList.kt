package dev.surefold.fail

/** A read-only list with at least one element, so [head] and `first()` never fail. */
class NonEmptyList<out A> private constructor(private val all: List<A>) : List<A> by all {

    val head: A get() = all[0]

    val tail: List<A> get() = all.subList(1, all.size)

    fun <B> map(transform: (A) -> B): NonEmptyList<B> = NonEmptyList(all.map(transform))

    operator fun plus(element: @UnsafeVariance A): NonEmptyList<A> = NonEmptyList(all + element)

    operator fun plus(elements: Iterable<@UnsafeVariance A>): NonEmptyList<A> = NonEmptyList(all + elements)

    override fun equals(other: Any?): Boolean = all == other

    override fun hashCode(): Int = all.hashCode()

    override fun toString(): String = all.toString()

    internal companion object {
        fun <A> wrap(elements: List<A>): NonEmptyList<A> = NonEmptyList(elements)
    }
}

fun <A> nonEmptyListOf(head: A, vararg tail: A): NonEmptyList<A> = NonEmptyList.wrap(listOf(head) + tail)

// toList copies, so a caller's mutable list cannot later change, or empty, the one handed out.
fun <A> Iterable<A>.toNonEmptyListOrNull(): NonEmptyList<A>? = toList().takeIf {
    it.isNotEmpty()
}?.let { NonEmptyList.wrap(it) }
