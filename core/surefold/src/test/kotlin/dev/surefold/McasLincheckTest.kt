package dev.surefold

import java.util.concurrent.TimeUnit
import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param
import org.jetbrains.lincheck.datastructures.StressOptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

private const val VALUES = 3

/**
 * Lincheck runs these operations from several threads and fails on any result that no one-at-a-time order of the same
 * operations could give. Values come from a fixed set of instances, because the swap compares by identity.
 */
// Lincheck instruments the classes once, which takes most of a minute on its own, and whichever test runs first pays.
@Timeout(5, unit = TimeUnit.MINUTES)
@Param(name = "value", gen = IntGen::class, conf = "0:2")
class McasLincheckTest {

    private val values = List(VALUES) { Any() }
    private val words = List(3) { Word(values[0]) }

    private fun read(word: Int): Int = values.indexOf(words[word].read())

    private fun swap(vararg changes: Triple<Int, Int, Int>): Boolean =
        swapAll(changes.map { (word, expected, desired) -> Change(words[word], values[expected], values[desired]) })

    @Operation fun read0() = read(0)

    @Operation fun read1() = read(1)

    @Operation fun read2() = read(2)

    @Operation fun swap0(@Param(name = "value") e: Int, @Param(name = "value") d: Int) = swap(Triple(0, e, d))

    @Operation
    fun swap01(
        @Param(name = "value") e0: Int,
        @Param(name = "value") d0: Int,
        @Param(name = "value") e1: Int,
        @Param(name = "value") d1: Int,
    ) = swap(Triple(0, e0, d0), Triple(1, e1, d1))

    @Operation
    fun swap12(
        @Param(name = "value") e1: Int,
        @Param(name = "value") d1: Int,
        @Param(name = "value") e2: Int,
        @Param(name = "value") d2: Int,
    ) = swap(Triple(1, e1, d1), Triple(2, e2, d2))

    @Operation
    fun swap210(
        @Param(name = "value") e2: Int,
        @Param(name = "value") d2: Int,
        @Param(name = "value") e1: Int,
        @Param(name = "value") d1: Int,
        @Param(name = "value") e0: Int,
        @Param(name = "value") d0: Int,
    ) = swap(Triple(2, e2, d2), Triple(1, e1, d1), Triple(0, e0, d0))

    @Test
    fun `every interleaving the model checker tries is linearizable`() =
        ModelCheckingOptions()
            .threads(3)
            .actorsPerThread(2)
            .iterations(10)
            .invocationsPerIteration(200)
            .check(this::class)

    @Test
    fun `four threads under stress are linearizable`() =
        StressOptions().threads(4).actorsPerThread(2).iterations(20).invocationsPerIteration(1_000).check(this::class)
}
