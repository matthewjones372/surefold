@file:OptIn(KafkaSpi::class)

package io.github.matthewjones372.surefold.kafka

import dev.surefold.fail.Either
import dev.surefold.fail.fold
import dev.surefold.fail.onLeft
import io.github.matthewjones372.surefold.stream.Stream
import io.github.matthewjones372.surefold.stream.mapConcat
import io.github.matthewjones372.surefold.stream.mapOrFail
import io.github.matthewjones372.surefold.stream.mapPar

/**
 * Each `Left` handed to [to], one at a time on a virtual thread and in order, before anything after it moves on.
 * Its offset is committed with the next `Right` on its partition, so a `Left` that is the last record read is
 * handed over again after a restart. A throw from [to] is a defect, and the record is not committed.
 */
fun <E, L : Any, R : Any> Stream<E, Committed<Either<L, R>>>.divertLefts(to: (L) -> Unit): Stream<E, Committed<R>> =
    // A function rather than surefold-stream's Sink: a sink's write is not ordered with the commit.
    mapPar(1) { c: Committed<Either<L, R>> -> c.also { it.annotated { either -> either.onLeft(to) } } }
        .mapConcat { c -> c.value.fold({ emptyList() }, { right -> listOf(c.carrying(right)) }) }

/** The first `Left` ends the run as `Failed`, with what came before it committed. */
fun <E, L : E, R : Any> Stream<E, Committed<Either<L, R>>>.absolve(): Stream<E, Committed<R>> =
    mapOrFail<E, Committed<Either<L, R>>, Committed<R>> { c -> c.carrying(c.value.fold({ fail(it) }, { it })) }
