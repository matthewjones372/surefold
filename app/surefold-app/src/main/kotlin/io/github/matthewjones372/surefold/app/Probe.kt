package io.github.matthewjones372.surefold.app

import dev.surefold.timeoutOrNull
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.time.Duration

/** When a probe is first asked on the way up: once its node is built, or once every node is (spec 0129). */
enum class Asked {
    WhenBuilt,

    /** After every node is built and every `onStarted` has run, for an answer that waits on the rest of the graph. */
    WhenStarted,
}

/** A question a node answers about itself, and the name the answer is reported under. */
class Probe internal constructor(
    val name: String,
    val key: KType,
    val timeout: Duration,
    val critical: Boolean,
    val attempts: Int,
    val interval: Duration,
    val asked: Asked,
    internal val ask: (Any) -> Boolean,
)

/**
 * A node is started when its probe answers, not when its recipe returns: a pool with no connection and
 * a consumer with no assignment have both been constructed and neither can serve anything.
 */
inline fun <reified A : Any> Module.probe(
    name: String,
    timeout: Duration,
    critical: Boolean = true,
    attempts: Int = 1,
    interval: Duration = Duration.ZERO,
    asked: Asked = Asked.WhenBuilt,
    noinline ask: (A) -> Boolean,
): Module = probed(name, typeOf<A>(), timeout, critical, attempts, interval, asked) { ask(it as A) }

/** What asking a probe came to: yes, or no with whatever it threw instead of answering. */
internal sealed interface Answer {
    data object Yes : Answer

    data class No(val cause: Exception?) : Answer
}

/**
 * The probe asked once, inside its timeout. A probe that throws has not said the thing is well, so the throw is a
 * no, and is kept to say why (spec 0126). An interruption is not caught: it is the timeout's race, or the caller's
 * stop, and either is theirs to see.
 */
@Suppress("TooGenericExceptionCaught") // a probe is a question about something failing; any failure is the answer
internal fun Probe.answered(value: Any): Answer =
    try {
        if (timeoutOrNull(timeout) { ask(value) } == true) Answer.Yes else Answer.No(null)
    } catch (stop: InterruptedException) {
        throw stop
    } catch (thrown: Exception) {
        Answer.No(thrown)
    }

@PublishedApi
internal fun Module.probed(
    name: String,
    key: KType,
    timeout: Duration,
    critical: Boolean,
    attempts: Int,
    interval: Duration,
    asked: Asked,
    ask: (Any) -> Boolean,
): Module =
    Module.of(nodes, probes + Probe(name, key, timeout, critical, attempts, interval, asked, ask), shadows, pending)
