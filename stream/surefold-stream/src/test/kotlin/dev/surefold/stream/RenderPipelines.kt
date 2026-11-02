package dev.surefold.stream

import kotlin.time.Duration.Companion.seconds

// The pipelines the golden renderings are of: 0046's four baseline benchmarks, and one fan-in. They are
// in a file of their own because a rendering names the line each operator was written on, and nothing
// else here should move them.

internal data class Odd(val value: Long)

private val ints = (0 until 100_000).toList()

internal val chain: Run<Odd, Long> =
    Stream.from(ints)
        .map { it.toLong() }
        .map { it * 3 }
        .filter { it % 2 == 0L }
        .map { it + 1 }
        .mapOrFail { if (it % 2 == 0L) fail(Odd(it)) else it }
        .runFold(0L) { total, n -> total + n }

internal val parallel: Run<Nothing, Long> =
    Stream.from(ints)
        .mapPar(8) { it.toLong() * 3 }
        .runFold(0L) { total, n -> total + n }

internal val batched: Run<Nothing, Int> =
    Stream.from(ints)
        .groupedWithin(100, 1.seconds)
        .runFold(0) { total, batch -> total + batch.size }

internal val perRequest: Run<Nothing, Long> =
    Stream.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        .map { it.toLong() }
        .filter { it % 2 == 0L }
        .runFold(0L) { total, n -> total + n }

internal val fanIn: Run<Nothing, List<Int>> =
    Stream.of(1, 2, 3)
        .map { it * 2 }
        .merge(Stream.of(4, 5).filter { it > 4 }.take(1))
        .zip(Stream.of("a", "b", "c"))
        .map { (n, s) -> n + s.length }
        .runCollect()
