package dev.surefold.actor.pekko

import dev.surefold.VirtualThreads
import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.javadsl.ActorContext
import org.apache.pekko.actor.typed.javadsl.Behaviors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

/**
 * [stage]'s answer, sent back to this actor as [wrap] of it (spec 0058), so the actor touches its state only on its
 * own turn. A declared failure arrives as a `Left`. A stage that fails with a throw is a defect: wrapping it throws
 * on the actor's turn, the actor fails, and its supervision decides what comes next. Pekko's `pipeToSelf`, with an
 * `Either` where it hands over a value and a throwable, one of them null.
 */
fun <T : Any, E, A> ActorContext<T>.pipeToSelf(
    stage: CompletionStage<Either<E, A>>,
    wrap: (Either<E, A>) -> T,
): Behavior<T> {
    pipeToSelf(stage) { answer, thrown ->
        if (thrown != null) throw (thrown as? CompletionException)?.cause ?: thrown
        wrap(answer)
    }
    return Behaviors.same()
}

/**
 * [block] run on [on], a virtual thread of its own unless told otherwise, and its answer sent back to this actor as
 * [wrap] of it: what it returned on the right, what it failed with on the left. A `Fail` never crosses a thread, so
 * the block fails into its own and the actor reads the outcome as a message. A throw fails the actor, as above.
 */
fun <T : Any, E, A> ActorContext<T>.pipeToSelf(
    wrap: (Either<E, A>) -> T,
    on: Executor = VirtualThreads,
    block: Fail<E>.() -> A,
): Behavior<T> = pipeToSelf(CompletableFuture.supplyAsync({ attempt(block) }, on), wrap)
