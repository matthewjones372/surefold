package dev.surefold.fail

/**
 * The scope [accumulate] opens: a [record]ed error is kept and the block carries on, while a [fail] keeps its error too
 * but ends the block. Every kept error fails the enclosing scope together once the block is over.
 */
interface Accumulating<in E> : FailAll<E> {
    /** Keeps [error] and carries on. */
    fun record(error: E)
}

/**
 * Runs [block], keeping every error it records or fails with; answers its value if it kept none, and fails this scope
 * with all of them, in the order they came, if it kept any.
 */
fun <E, A> Fail<NonEmptyList<E>>.accumulate(block: Accumulating<E>.() -> A): A {
    var collector: Collector<E>? = null
    val outcome =
        try {
            attempt { Collector(this, block.javaClass.name).also { collector = it }.block() }
        } finally {
            collector?.close()
        }
    val recorded = checkNotNull(collector).recorded
    return outcome.fold({ fail(it) }, { value -> recorded.toNonEmptyListOrNull()?.let { fail(it) } ?: value })
}

context(scope: Accumulating<E>)
inline fun <E> ensureOrAccumulate(condition: Boolean, error: () -> E) {
    if (!condition) scope.record(error())
}

/** The value on the right, or null once the error on the left has been recorded. */
context(scope: Accumulating<E>)
fun <E, A> Either<E, A>.bindOrAccumulate(): A? =
    fold(
        {
            scope.record(it)
            null
        },
        { it },
    )

/**
 * Keeps errors for one [accumulate] block. Like an attempt, it records only while the block runs and only on the thread
 * that opened it; a fail goes out through [ending], with the errors recorded before it.
 */
private class Collector<E>(private val ending: Fail<NonEmptyList<E>>, private val openedIn: String) : Accumulating<E> {

    private val owner = Thread.currentThread()

    private val kept = mutableListOf<E>()

    @Volatile private var closed = false

    val recorded: List<E>
        get() = kept

    override fun record(error: E) {
        check(!closed) { "an accumulate block opened in $openedIn was recorded into after its block had returned" }
        check(Thread.currentThread() === owner) {
            "an accumulate block opened in $openedIn was recorded into from ${Thread.currentThread()}, " +
                "not the thread that opened it"
        }
        kept += error
    }

    override fun fail(error: E): Nothing = failAll(nonEmptyListOf(error))

    override fun failAll(errors: NonEmptyList<E>): Nothing {
        val all = kept.toNonEmptyListOrNull()?.plus(errors) ?: errors
        ending.fail(all)
    }

    fun close() {
        closed = true
    }
}
