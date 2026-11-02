package dev.surefold.fail

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/** The scope a declared failure of type [E] leaves through; [attempt] opens one and answers with how it ended. */
interface Fail<in E> {
    /** Leaves the scope with [error], which its [attempt] answers on the left. */
    fun fail(error: E): Nothing
}

/**
 * The scope [attempt] opens: it fails only while open and only on the thread that opened it, and only its own
 * attempt catches what it throws.
 */
internal class Attempt<in E>(private val openedIn: String) : Fail<E> {

    private val owner = Thread.currentThread()

    @Volatile
    private var closed = false

    override fun fail(error: E): Nothing {
        // A Fail that escaped its attempt, in a lambda or a fork still running, has nobody left to answer.
        check(!closed) { "fail was called on a scope opened in $openedIn after its attempt had returned" }
        // Thrown on another thread, the failure would end that thread rather than the attempt. A fork fails
        // into a scope of its own, which its combinator then surfaces here.
        check(Thread.currentThread() === owner) {
            "fail was called on a scope opened in $openedIn from ${Thread.currentThread()}, a thread it did not fork"
        }
        throw Failed(error, this)
    }

    fun close() {
        closed = true
    }
}

/**
 * How [fail] leaves the block. It extends Throwable rather than Exception so that `catch (e: Exception)` in
 * the block cannot swallow it, and records no stack trace because nothing ever prints it.
 */
internal class Failed(val error: Any?, val scope: Fail<*>) : Throwable(null, null, false, false)

/** Runs [block], answering with its value on the right, or on the left with the error it failed with. */
fun <E, A> attempt(block: Fail<E>.() -> A): Either<E, A> {
    val scope = Attempt<E>(block.javaClass.name)
    return try {
        Either.Right(scope.block())
    } catch (failed: Failed) {
        if (failed.scope !== scope) throw failed
        // Only fail on this same Attempt<E> threw it, so the error is an E.
        @Suppress("UNCHECKED_CAST")
        Either.Left(failed.error as E)
    } finally {
        scope.close()
    }
}

/**
 * Runs [f] on each element in order on the calling thread, answering with every value in order, or with the first
 * failure, after which no later element runs (spec 0245).
 */
fun <E, A, B> Iterable<A>.traverse(f: Fail<E>.(A) -> B): Either<E, List<B>> = attempt { map { f(it) } }

/** Runs [block], answering a failure with [recover] instead. */
inline fun <E, A> recover(noinline block: Fail<E>.() -> A, recover: (E) -> A): A = attempt(block).getOrElse(recover)

context(scope: Fail<E>)
fun <E> fail(error: E): Nothing = scope.fail(error)

context(scope: Fail<E>)
fun <E, A> Either<E, A>.bind(): A = fold({ scope.fail(it) }, { it })

@OptIn(ExperimentalContracts::class)
context(scope: Fail<E>)
inline fun <E> ensure(condition: Boolean, error: () -> E) {
    contract { returns() implies condition }
    if (!condition) fail(error())
}

@OptIn(ExperimentalContracts::class)
context(scope: Fail<E>)
inline fun <E, A : Any> ensureNotNull(value: A?, error: () -> E): A {
    contract { returns() implies (value != null) }
    return value ?: fail(error())
}
