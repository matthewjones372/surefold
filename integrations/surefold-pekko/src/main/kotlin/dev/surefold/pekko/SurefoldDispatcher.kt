package dev.surefold.pekko

import org.apache.pekko.actor.ClassicActorSystemProvider
import java.util.concurrent.Executor

private const val VIRTUAL_THREADS = "virtual-thread-executor"

private const val PINNED = "PinnedDispatcher"

/**
 * The dispatcher configured at `pekko.actor.[id]`, as the executor surefold's forks run on.
 *
 * A surefold fork blocks: it parks a thread on a JDBC call or on `await()` and answers when that returns. On a
 * `fork-join-executor` or a `thread-pool-executor` that is the starvation Pekko's own documentation warns
 * about, so those are refused here rather than documented — a virtual thread per fork, or a thread the
 * dispatcher hands to nothing else.
 */
fun ClassicActorSystemProvider.surefoldDispatcher(id: String = "surefold"): Executor {
    val path = "pekko.actor.$id"
    val system = classicSystem()
    // Looked up before the config is read, so an id nobody configured leaves as Pekko's own error rather
    // than as Typesafe Config's complaint about a missing path.
    val dispatcher = system.dispatchers().lookup(path)
    val config = system.settings().config().getConfig(path)
    val type = config.takeIf { it.hasPath("type") }?.getString("type")
    val executor = config.takeIf { it.hasPath("executor") }?.getString("executor")
    require(executor == VIRTUAL_THREADS || type == PINNED) {
        "$path.executor is ${executor?.let { "\"$it\"" } ?: "unset"}: surefold takes \"$VIRTUAL_THREADS\", " +
            "or a dispatcher with type = $PINNED, and not a pool its forks would starve."
    }
    return dispatcher
}
