package dev.surefold.stream

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBeEmpty
import org.apache.pekko.actor.ActorSystem
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The operators whose edges are easy to get subtly wrong, run over a grid of their arguments and of
 * stream lengths, with Pekko's answer as the one every other backend has to give.
 */
object DifferentialSuite : SharedSuite<ActorSystem>() {
    override val shared: Resource<ActorSystem> = actorSystem("surefold-stream-differential-test")

    private const val SETTLE_SECONDS = 10L

    private val others: List<Backend> = listOf(OnForks, OnTestStreams, OnActors)

    private fun <R : Any> Run<*, R>.on(backend: StreamBackend): Exit<*, R> =
        run(backend).toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)

    /** An exit as the backends can agree on it: a `Died` by its cause's type and message, not its identity. */
    private fun Exit<*, *>.comparable(): Any =
        when (this) {
            is Exit.Died -> "Died(${cause::class.simpleName}: ${cause.message})"
            else -> this
        }

    /** The cases of one test that some backend answered differently from Pekko. */
    private class Disagreements(val pekko: StreamBackend, val others: List<StreamBackend>) {
        val found = mutableListOf<String>()
    }

    /** Runs every case in [cases] and then fails once, naming each that disagreed, rather than at the first. */
    private fun everyCase(system: ActorSystem, cases: Disagreements.() -> Unit) =
        Disagreements(OnPekko.on(system), others.map { it.on(system) }).apply(cases).found.shouldBeEmpty()

    private fun <R : Any> Disagreements.agree(what: String, run: () -> Run<*, R>) {
        val expected = run().on(pekko).comparable()
        others.forEach { backend ->
            val answer = run().on(backend).comparable()
            if (answer != expected) found += "$what on ${backend.key}: expected $expected but was $answer"
        }
    }

    /** A stage that completes after a delay drawn from [seed], so stages finish out of the order they began. */
    private fun later(n: Int, seed: Int): CompletableFuture<Int> =
        CompletableFuture.supplyAsync(
            { n * 10 },
            CompletableFuture.delayedExecutor(Random(seed * 31 + n).nextLong(0, 3), TimeUnit.MILLISECONDS),
        )

    init {
        test("sliding answers as Pekko's does, for every window, step and length") { system ->
            everyCase(system) {
                for (n in 1..4) {
                    for (step in 1..6) {
                        for (length in 0..13) {
                            agree("sliding($n, $step) over $length") {
                                Stream.from(1..length).sliding(n, step).runCollect()
                            }
                        }
                    }
                }
            }
        }

        test("interleave answers as Pekko's does, for every segment and both lengths") { system ->
            everyCase(system) {
                for (segment in 1..3) {
                    for (left in 0..7) {
                        for (right in 0..7) {
                            agree("interleave($segment) of $left and $right") {
                                Stream.from(1..left).interleave(Stream.from(101..(100 + right)), segment).runCollect()
                            }
                        }
                    }
                }
            }
        }

        test("mapAsync answers as Pekko's does, in order, whatever order its stages complete in") { system ->
            everyCase(system) {
                for (parallelism in 1..4) {
                    for (length in 0..10) {
                        agree("mapAsync($parallelism) over $length") {
                            Stream.from(1..length).mapAsync(parallelism) { n -> later(n, parallelism) }.runCollect()
                        }
                    }
                }
            }
        }

        test("mapAsync dies as Pekko's does, on a stage that fails and on one that completes with null") { system ->
            everyCase(system) {
                for (at in 1..4) {
                    agree("mapAsync failing at $at") {
                        Stream.from(1..5).mapAsync(2) { n ->
                            if (n ==
                                at
                            ) CompletableFuture.failedFuture(IllegalStateException("no $n")) else later(n, at)
                        }.runCollect()
                    }
                    agree("mapAsync completing with null at $at") {
                        Stream.from(1..5).mapAsync(2) { n ->
                            @Suppress("UNCHECKED_CAST")
                            if (n == at) {
                                CompletableFuture.completedFuture(null) as CompletableFuture<Int>
                            } else {
                                later(n, at)
                            }
                        }.runCollect()
                    }
                }
            }
        }
    }
}
