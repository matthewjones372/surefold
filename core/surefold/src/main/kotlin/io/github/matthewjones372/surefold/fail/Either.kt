package io.github.matthewjones372.surefold.fail

import kotlin.reflect.KClass

/** A value that is either the error [E] on the left or the result [A] on the right. */
sealed interface Either<out E, out A> {

    data class Left<out E>(val value: E) : Either<E, Nothing>

    data class Right<out A>(val value: A) : Either<Nothing, A>

    fun isLeft(): Boolean = this is Left

    fun isRight(): Boolean = this is Right

    fun getOrNull(): A? = (this as? Right)?.value

    fun leftOrNull(): E? = (this as? Left)?.value

    fun swap(): Either<A, E> = fold({ Right(it) }, { Left(it) })

    /** Unmatched failures keep their identity and remain on the left. */
    fun <T : Any> recover(
        type: KClass<T>,
        handler: Fail<@UnsafeVariance E>.(T) -> @UnsafeVariance A,
    ): Either<E, A> = when (this) {
        is Left -> {
            val selected = type.javaObjectType
            if (selected.isInstance(value)) {
                val narrowed = selected.cast(value)
                attempt { handler(narrowed) }
            } else {
                this
            }
        }

        is Right -> this
    }

    companion object {
        inline fun <E, A> cond(condition: Boolean, right: () -> A, left: () -> E): Either<E, A> =
            if (condition) Right(right()) else Left(left())

        /**
         * What [block] returned, or on the left what it threw that a caller may handle. Anything fatal, and a
         * [fail] on its way out of its block, goes on up: see [nonFatalOrThrow].
         */
        @Suppress("TooGenericExceptionCaught") // catching everything is the point; nonFatalOrThrow sorts it
        inline fun <A> catch(block: () -> A): Either<Throwable, A> =
            try {
                Right(block())
            } catch (thrown: Throwable) {
                Left(thrown.nonFatalOrThrow())
            }
    }
}

inline fun <E, A, B> Either<E, A>.fold(ifLeft: (E) -> B, ifRight: (A) -> B): B = when (this) {
    is Either.Left -> ifLeft(value)
    is Either.Right -> ifRight(value)
}

inline fun <E, A, B> Either<E, A>.map(f: (A) -> B): Either<E, B> = when (this) {
    is Either.Left -> this
    is Either.Right -> Either.Right(f(value))
}

inline fun <E, F, A> Either<E, A>.mapLeft(f: (E) -> F): Either<F, A> = when (this) {
    is Either.Left -> Either.Left(f(value))
    is Either.Right -> this
}

inline fun <E, A, B> Either<E, A>.flatMap(f: (A) -> Either<E, B>): Either<E, B> = when (this) {
    is Either.Left -> this
    is Either.Right -> f(value)
}

inline fun <E, A> Either<E, A>.getOrElse(ifLeft: (E) -> A): A = fold(ifLeft) { it }

inline fun <E, A> Either<E, A>.onLeft(action: (E) -> Unit): Either<E, A> = also {
    if (it is Either.Left) action(it.value)
}

inline fun <E, A> Either<E, A>.onRight(action: (A) -> Unit): Either<E, A> = also {
    if (it is Either.Right) action(it.value)
}

/** The value on whichever side it is, where both sides are the same type. */
fun <A> Either<A, A>.merge(): A = fold({ it }, { it })

fun <E> E.left(): Either<E, Nothing> = Either.Left(this)

fun <A> A.right(): Either<Nothing, A> = Either.Right(this)

/** Recovery can bind an alternative or fail with a new declared error type. */
fun <E, F, A> Either<E, A>.recover(handler: Fail<F>.(E) -> A): Either<F, A> = when (this) {
    is Either.Left -> attempt { handler(value) }
    is Either.Right -> this
}
