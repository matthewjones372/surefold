package dev.surefold.stream

import java.util.concurrent.CompletionException

/**
 * What a defect is reported with beyond its cause: the operator, the element it was processing,
 * and the caller's line that built the operator.
 *
 * It rides along as a suppressed exception rather than as a wrapper, so that `Died` still carries
 * the class the caller's own code threw and the three facts print in the stack trace beside it.
 */
@StreamSpi
class Defect(operator: String, element: Any, at: String) :
    RuntimeException(facts(operator, element, at), null, false, false)

/** The sentence a defect is named by, in a log line and in a message the library raises alike. */
@StreamSpi
fun facts(operator: String, element: Any, at: String): String = "$operator died on $element, built at $at"

/**
 * The caller's [f], with the three facts attached to whatever it throws: the one wiring every
 * operator that runs caller code shares.
 *
 * [at] is read while the pipeline is described and the element is asked for its `toString` only
 * once something has already gone wrong, so a running stream pays for neither.
 */
@StreamSpi
fun <A : Any, B> guarded(operator: String, at: String, f: (A) -> B): (A) -> B =
    { a -> guard(operator, at, a, f) }

/** As above for a body handed the declared failure rather than an element, which may be anything. */
@StreamSpi
fun <E, E2> guardedError(operator: String, at: String, f: (E) -> E2): (E) -> E2 =
    { e -> guard(operator, at, e ?: "no error") { f(e) } }

/** As above for a body of two, where the element a defect names is the one being folded in. */
@StreamSpi
fun <S, A : Any, B> guarded(operator: String, at: String, f: (S, A) -> B): (S, A) -> B =
    { s, a -> guard(operator, at, s, a, f) }

// The catch is as wide as the lambda, because a defect is everything the caller did not declare;
// which throwable is one is decided below.
@Suppress("TooGenericExceptionCaught")
private fun <A : Any, B> guard(operator: String, at: String, a: A, f: (A) -> B): B =
    try {
        f(a)
    } catch (thrown: Throwable) {
        throw thrown.describedBy(operator, a, at)
    }

// The pair above rather than one over a `Pair`: the body is called once per element, and a wrapper
// built for it would be an allocation per element in every operator that runs caller code.
@Suppress("TooGenericExceptionCaught")
private fun <S, A : Any, B> guard(operator: String, at: String, s: S, a: A, f: (S, A) -> B): B =
    try {
        f(s, a)
    } catch (thrown: Throwable) {
        throw thrown.describedBy(operator, a, at)
    }

/** The wrapper a declared failure travels in goes by untouched; everything else picks up the facts. */
@StreamSpi
fun Throwable.describedBy(operator: String, element: Any, at: String): Throwable {
    if (this !is DeclaredFailure) addSuppressed(Defect(operator, element, at))
    return this
}

/** What actually failed a stage: a CompletableFuture reports it wrapped in a CompletionException. */
@StreamSpi
fun Throwable.unwrapped(): Throwable = if (this is CompletionException) cause ?: this else this

private val walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)

/** Where a class file came from, read once per class: a build site is taken on every run, and a URL's text costs. */
private val codeSources = object : ClassValue<String?>() {
    override fun computeValue(type: Class<*>): String? = type.protectionDomain?.codeSource?.location?.toString()
}

private fun codeOf(type: Class<*>): String? = codeSources.get(type)

private val ownCode: String? = codeOf(Stream::class.java)

/**
 * The caller's line that built an operator, as `File.kt:12`.
 *
 * A frame is the library's own by where its class file came from rather than by its package,
 * because this library's own tests sit in its package and have to read as callers like anyone else.
 * A backend module passes a class of its own in [alsoLibrary], so its frames are skipped too.
 */
@StreamSpi
fun buildSite(vararg alsoLibrary: Class<*>): String {
    val library = setOf(ownCode) + alsoLibrary.map(::codeOf)
    return walker.walk { frames ->
        frames.filter { frame -> codeOf(frame.declaringClass) !in library }
            .findFirst()
            .map { frame -> "${frame.fileName}:${frame.lineNumber}" }
            .orElse("a line nobody could name")
    }
}
