package dev.surefold

import java.util.concurrent.TimeUnit
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.StressOptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Spec 0231: steps that run alone and steps that do not, from several threads at once, lose no write and are never seen
 * half done. Each step moves both values together, so a read that sees them differ, or a total that is not what some
 * one-at-a-time order gives, fails.
 *
 * Stress only: the model checker resets static state between its runs, so the word ids, and the commit count and solo
 * words every step shares, would start over while those words live on, and two words would share an id.
 */
// Lincheck instruments the classes once, which takes most of a minute on its own, and whichever test runs first pays.
@Timeout(5, unit = TimeUnit.MINUTES)
class SoloLincheckTest {

    private val stalls = Shared(0)
    private val circle = Shared(0)

    @Operation
    fun hold() = transact {
        stalls.update { it + 1 }
        circle.update { it + 1 }
    }

    @Operation
    fun holdAlone() =
        transact(soloAfter = 0) {
            stalls.update { it + 1 }
            circle.update { it + 1 }
        }

    @Operation fun held(): Pair<Int, Int> = transact { stalls.get() to circle.get() }

    @Test
    fun `four threads under stress are linearizable`() =
        StressOptions().threads(4).actorsPerThread(2).iterations(20).invocationsPerIteration(1_000).check(this::class)
}
