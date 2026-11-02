package dev.surefold.stream

import dev.surefold.await
import dev.surefold.fail.Fail
import java.util.concurrent.CompletionStage

/**
 * Waits for a run on the calling thread and answers with what it ended with: `Done` is the value,
 * `Failed` fails with the error here, and `Died` throws the cause nobody declared.
 *
 * The wait is `surefold-pekko`'s, so an interrupt cancels the run's stage rather than abandoning it. Kotlin
 * has no receiver to spare for a `Fail<E>` and a `CompletionStage` at once, so the stage is the
 * argument: `awaitExit(pipeline.run(system))` inside an `attempt { }`.
 */
fun <E, R : Any> Fail<E>.awaitExit(stage: CompletionStage<Exit<E, R>>): R = when (val exit = stage.await()) {
    is Exit.Done -> exit.value
    is Exit.Failed -> fail(exit.error)
    is Exit.Died -> throw exit.cause
}
