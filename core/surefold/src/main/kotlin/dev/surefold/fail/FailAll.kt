package dev.surefold.fail

/** A [Fail] that can also leave with several errors at once, which its accumulating combinator keeps together. */
interface FailAll<in E> : Fail<E> {
    /** Leaves the scope with every one of [errors]. */
    fun failAll(errors: NonEmptyList<E>): Nothing
}

/** The value on the right, or every error on the left, kept rather than cut to the first. */
context(scope: FailAll<E>)
fun <E, A> Either<NonEmptyList<E>, A>.bindAll(): A = fold({ scope.failAll(it) }, { it })
