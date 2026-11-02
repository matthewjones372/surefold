package io.github.matthewjones372.surefold.app

import dev.surefold.Promise
import dev.surefold.awaitValue
import dev.surefold.fail.Either
import dev.surefold.fail.fold
import kotlin.time.Duration.Companion.seconds

// A release that has not finished by then is one the process stops waiting for: a shutdown hook that
// never returns is a container that has to be killed rather than stopped.
private val TEARDOWN_LIMIT = 30.seconds

/** The process being asked to stop, as a value, so a test can ask without a signal. */
class Shutdown {

    private val requested = Promise<Nothing, Unit>("surefold.app.shutdown")

    fun request() {
        requested.succeed(Unit)
    }

    internal fun await() {
        requested.awaitValue()
    }
}

/** What a running application is handed. */
interface AppScope {

    /** Returns when the process is asked to stop. */
    fun awaitShutdown()
}

/** Starts the graph, runs [block] against the node it asks for, and releases everything after. */
inline fun <reified A : Any> Module.application(
    shutdown: Shutdown,
    noinline block: AppScope.(A) -> Unit,
): Either<StartupError, Unit> = use<A, Unit> { root -> appScope(shutdown).block(root) }

@PublishedApi
internal fun appScope(shutdown: Shutdown): AppScope = object : AppScope {
    override fun awaitShutdown() = shutdown.await()
}

/** What went wrong, in the words the reader needs to fix it. */
fun StartupError.describe(): String = when (this) {
    is StartupError.Unwireable -> errors.report(provided)

    is StartupError.Refused -> "surefold-app: ${labelOf(key)} refused to start: $reason"

    is StartupError.NoSuchNode -> "surefold-app: nothing in the graph builds ${labelOf(key)}"

    is StartupError.Unready ->
        "surefold-app: ${labelOf(key)} started and its $name probe did not answer" +
            cause?.let { ": it threw ${it::class.simpleName}: ${it.message}" }.orEmpty()
}

/** What the process should leave with. */
@JvmInline
value class ExitCode(val code: Int) {

    companion object {
        val Ok: ExitCode = ExitCode(0)
        val Failed: ExitCode = ExitCode(1)
    }
}

/** [ExitCode.Ok] only when the application left of its own accord. */
fun Either<StartupError, Unit>.exitCode(): ExitCode = fold({ ExitCode.Failed }, { ExitCode.Ok })

/**
 * The whole of a `main` bar the leaving: a signal stops the application, its releases run before this
 * answers, and what went wrong is on stderr.
 *
 * It answers with an [ExitCode] rather than ending the process, so a test can run an application and
 * read what it decided. `exitProcess` is the caller's, and worth making: a stray non-daemon thread
 * keeps a JVM alive after `main` returns.
 *
 * ```kotlin
 * fun main() {
 *     exitProcess(runApp(app) { server: HttpServer -> server.start(); awaitShutdown() }.code)
 * }
 * ```
 */
inline fun <reified A : Any> runApp(module: Module, noinline block: AppScope.(A) -> Unit): ExitCode =
    leaving { shutdown -> module.application(shutdown, block) }

@PublishedApi
internal fun leaving(run: (Shutdown) -> Either<StartupError, Unit>): ExitCode {
    val shutdown = Shutdown()
    val torndown = Promise<Nothing, Unit>("surefold.app.teardown")
    val hook = Thread {
        shutdown.request()
        torndown.await(within = TEARDOWN_LIMIT)
    }

    Runtime.getRuntime().addShutdownHook(hook)
    val outcome = uncaughtLogged { run(shutdown) }
    torndown.succeed(Unit)
    // Removing it during a shutdown it is already running in is the illegal state, and by then the
    // process is leaving anyway.
    try {
        Runtime.getRuntime().removeShutdownHook(hook)
    } catch (_: IllegalStateException) {
        // The hook is running.
    }

    outcome.leftOrNull()?.let { System.err.println(it.describe()) }
    return outcome.exitCode()
}
