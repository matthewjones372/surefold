package io.github.matthewjones372.surefold.stream

import dev.surefold.VirtualThreads
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.fold
import dev.surefold.fail.nonFatalOrThrow
import io.github.matthewjones372.surefold.actor.ActorRef
import io.github.matthewjones372.surefold.actor.Behaviour
import io.github.matthewjones372.surefold.actor.Ctx
import io.github.matthewjones372.surefold.actor.behaviour
import io.github.matthewjones372.surefold.actor.stay
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * One run's operators that Forks forks for, as children of the run's actor. [ctx] is the run actor's, set by each
 * step before it pulls; a node built anywhere but that step (in a feeder's pull, say) is left to Forks, since only the
 * run's own step may spawn its children.
 */
internal class ActorBoundaries(private val batch: Int) : Boundaries {

    @Volatile
    private var stepping: Thread? = null
    private lateinit var ctx: Ctx<*>
    private var spawned = 0

    /** Runs [block] as the run actor's step, whose context spawns this run's boundaries. */
    fun <T> step(ctx: Ctx<*>, block: () -> T): T {
        this.ctx = ctx
        stepping = Thread.currentThread()
        return try {
            block()
        } finally {
            stepping = null
        }
    }

    override fun pull(node: Node, pulled: (Node) -> Pull): Pull? {
        if (stepping !== Thread.currentThread()) return null
        return when (node) {
            is Node.MapPar -> if (node.on === VirtualThreads) node.workers(pulled(node.upstream)) else null
            is Node.Buffer -> node.fed(pulled(node.upstream))
            is Node.Merge -> node.fed(pulled(node.upstream), pulled(node.other))
            is Node.FlatMap -> node.breadth?.let { breadth -> node.fed(breadth, pulled(node.upstream), pulled) }
            is Node.Conflate -> node.fed(pulled(node.upstream))
            else -> null
        }
    }

    private fun <M : Any, S, E> spawn(name: String, behaviour: Behaviour<M, S, E>): ActorRef<M> =
        ctx.spawn("$name-${++spawned}", behaviour, null)

    /**
     * `mapPar(n)` on [Node.MapPar.parallelism] worker actors, each told its elements in turn: at most `n` bodies are
     * in flight, and their answers come back in the order the elements came.
     */
    private fun Node.MapPar.workers(up: Pull): Pull {
        val body = guarded("mapPar", at) { a: Any -> attempt { f(a) } }
        val workers = List(parallelism) { spawn("mapPar", worker(body)) }
        val window = ArrayDeque<CompletableFuture<Either<Any?, Any>>>()
        var drained = false
        var told = 0
        return Pull {
            while (!drained && window.size < parallelism) {
                val a = up.next()
                if (a == null) {
                    drained = true
                } else {
                    val answer = CompletableFuture<Either<Any?, Any>>()
                    workers[told++ % parallelism].tell(Work(a, answer))
                    window.addLast(answer)
                }
            }
            window.removeFirstOrNull()?.let { head ->
                val answer = try {
                    head.get()
                } catch (failed: ExecutionException) {
                    throw failed.cause ?: failed
                }
                answer.fold({ e -> throw DeclaredFailure(e) }, { b -> b })
            }
        }
    }

    /** `buffer(n)`: an input actor pulls upstream ahead of the reader, while there is room for `n`. */
    private fun Node.Buffer.fed(up: Pull): Pull {
        val confluence = Inflow(room = size, open = 1, outerDone = true)
        spawn("buffer", Input(up, confluence, batch).behaviour())
        return Pull(confluence::next)
    }

    /** `merge`: each stream an input actor, both into one queue, read in the order their elements arrived. */
    private fun Node.Merge.fed(up: Pull, others: Pull): Pull {
        val confluence = Inflow(room = MERGED, open = 2, outerDone = true)
        spawn("merge", Input(up, confluence, batch).behaviour())
        spawn("merge", Input(others, confluence, batch).behaviour())
        return Pull(confluence::next)
    }

    /**
     * `flatMapMerge(breadth)`: an outer actor pulls the outer stream and starts each inner one as an input actor of
     * its own, while fewer than `breadth` are running.
     */
    private fun Node.FlatMap.fed(breadth: Int, up: Pull, pulled: (Node) -> Pull): Pull {
        val confluence = Inflow(room = MERGED, open = 0, outerDone = false)
        val build = guarded("flatMapMerge", at, f)
        val outer = Outer(up, { a -> pulled(build(a).node.optimised()) }, breadth, confluence, batch)
        spawn("flatMapMerge", outer.behaviour())
        return Pull(confluence::next)
    }

    /** `conflate`: an actor folds upstream into what is pending while the reader is slow, a batch at a time. */
    private fun Node.Conflate.fed(up: Pull): Pull {
        val pile = Heap(guarded("conflateWithSeed", at, seed), guarded("conflateWithSeed", at, aggregate))
        spawn("conflate", Folding(up, pile, batch).behaviour())
        return Pull(pile::take)
    }
}

/** One element for a `mapPar` worker, and where its answer goes. */
private class Work(val element: Any, val answer: CompletableFuture<Either<Any?, Any>>)

// Whatever a body threw is its answer, which the reader throws again in order; only a fatal throw is not.
@Suppress("TooGenericExceptionCaught")
private fun worker(body: (Any) -> Either<Any?, Any>): Behaviour<Work, Unit, Nothing> =
    behaviour<Work, Unit>(Unit) { _, _, work ->
        try {
            work.answer.complete(body(work.element))
        } catch (thrown: Throwable) {
            work.answer.completeExceptionally(thrown.nonFatalOrThrow())
        }
        stay()
    }
