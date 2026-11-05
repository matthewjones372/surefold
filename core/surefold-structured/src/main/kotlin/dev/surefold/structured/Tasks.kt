package dev.surefold.structured

import dev.surefold.fail.Fail
import java.util.concurrent.Callable
import java.util.concurrent.StructuredTaskScope
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.toJavaDuration

// Where a branch sits in the tree of scopes, bound for its body: a scope opened inside it is named by it.
private val path: ScopedValue<String> = ScopedValue.newInstance()

private val stack: StackWalker = StackWalker.getInstance()

// The frames of this module's own plumbing, skipped to find the function that asked for the scope.
private val plumbing = listOf("Tasks", "StructuredKt", "OutcomeKt").map { "dev.surefold.structured.$it" }

/**
 * One JDK scope for one combinator call. A thread dump shows it named after the function that called the combinator,
 * or, inside a branch, after that branch; its threads are that name with `/1`, `/2`, ….
 */
internal class Tasks<E, R>(
    joiner: StructuredTaskScope.Joiner<Outcome<E, Any?>, R, RuntimeException>,
    deadline: Duration? = null,
) : AutoCloseable {

    private val name: String = if (path.isBound) path.get() else callerName()
    private var forked = 0

    private val scope: StructuredTaskScope<Outcome<E, Any?>, R, RuntimeException> =
        StructuredTaskScope.open(joiner) { config ->
            val named = config.withName(name).withThreadFactory(Thread.ofVirtual().name("$name/", 1).factory())
            if (deadline == null) named else named.withTimeout(deadline.toJavaDuration())
        }

    fun <T> fork(body: Fail<E>.() -> T): StructuredTaskScope.Subtask<Outcome<E, T>> {
        forked += 1
        val branch = "$name/$forked"
        return scope.fork<Outcome<E, T>>(
            Callable {
                ScopedValue.where(path, branch)
                    .call(
                        ScopedValue.CallableOp<Outcome<E, T>, RuntimeException> { capture(body) },
                    )
            },
        )
    }

    fun join(): R = scope.join()

    override fun close() = scope.close()
}

private fun callerName(): String = stack.walk { frames ->
    val outside = frames.filter { frame -> plumbing.none { frame.className.startsWith(it) } }.toList()
    // The block given to `attempt` runs as a lambda of its own, called by `attempt`: the function that
    // opened the attempt, beneath both, is the one that asked for the scope.
    outside
        .filterIndexed { i, frame ->
            !frame.inAttempt() &&
                !(frame.methodName.contains("\$lambda\$") && outside.getOrNull(i + 1)?.inAttempt() == true)
        }
        .firstOrNull()
        ?.let(::describe) ?: "surefold"
}

private fun StackWalker.StackFrame.inAttempt(): Boolean = className.startsWith("dev.surefold.fail.")

// A Kotlin lambda's frame is `invoke` on a class named after the function that wrote it.
private fun describe(frame: StackWalker.StackFrame): String {
    val parts = frame.className.substringAfterLast('.').split('$')
    val function = if (frame.methodName == "invoke" && parts.size > 1) parts[1] else frame.methodName
    return "${parts[0]}.$function"
}

/** Answered before the scope was cancelled: after that, the JDK records nothing for a subtask. */
internal fun StructuredTaskScope.Subtask<*>.hasAnswered(): Boolean =
    state() == StructuredTaskScope.Subtask.State.SUCCESS

/** Every subtask succeeds as far as the JDK knows: a branch's failure is a value, read here. */
internal class FirstFailure<E> : StructuredTaskScope.Joiner<Outcome<E, Any?>, Failure<E>?, RuntimeException> {

    private val first = AtomicReference<Failure<E>?>()

    override fun onComplete(subtask: StructuredTaskScope.Subtask<Outcome<E, Any?>>): Boolean {
        val failure = subtask.get() as? Failure<E> ?: return false
        first.compareAndSet(null, failure)
        return true
    }

    override fun result(): Failure<E>? = first.get()

    override fun timeout(): Failure<E>? = first.get()
}

/** Cancels the scope at the first subtask to finish, whatever it finished with, and names it. */
internal class FirstToFinish<E> :
    StructuredTaskScope.Joiner<Outcome<E, Any?>, StructuredTaskScope.Subtask<*>?, RuntimeException> {

    private val first = AtomicReference<StructuredTaskScope.Subtask<*>?>()

    override fun onComplete(subtask: StructuredTaskScope.Subtask<Outcome<E, Any?>>): Boolean {
        first.compareAndSet(null, subtask)
        return true
    }

    override fun result(): StructuredTaskScope.Subtask<*>? = first.get()

    override fun timeout(): StructuredTaskScope.Subtask<*>? = first.get()
}

/** Says whether the scope's deadline passed before every subtask had finished. */
internal class Expiry<E> : StructuredTaskScope.Joiner<Outcome<E, Any?>, Boolean, RuntimeException> {

    override fun result(): Boolean = false

    override fun timeout(): Boolean = true
}
