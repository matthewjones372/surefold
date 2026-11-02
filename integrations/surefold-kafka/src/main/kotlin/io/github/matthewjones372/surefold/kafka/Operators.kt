@file:OptIn(KafkaSpi::class)

package io.github.matthewjones372.surefold.kafka

import dev.surefold.fail.Fail
import io.github.matthewjones372.surefold.stream.Failing
import io.github.matthewjones372.surefold.stream.Stream
import io.github.matthewjones372.surefold.stream.filter
import io.github.matthewjones372.surefold.stream.groupedWithin
import io.github.matthewjones372.surefold.stream.map
import io.github.matthewjones372.surefold.stream.mapConcat
import io.github.matthewjones372.surefold.stream.mapOrFail
import io.github.matthewjones372.surefold.stream.mapPar
import io.github.matthewjones372.surefold.stream.mapParKeyed
import io.github.matthewjones372.surefold.stream.mapParOrFail
import kotlin.time.Duration

// surefold-stream's operators with the body on the value and the offset carried through. Named apart from
// them because the overloads cannot be told apart: with both packages imported, `map` would be ambiguous.

fun <E, A : Any, B : Any> Stream<E, Committed<A>>.mapRecord(f: (A) -> B): Stream<E, Committed<B>> =
    map { c: Committed<A> -> c.carrying(c.annotated(f)) }

/** A filtered record is committed with the next record on its partition that is not. */
fun <E, A : Any> Stream<E, Committed<A>>.filterRecord(predicate: (A) -> Boolean): Stream<E, Committed<A>> =
    filter { c: Committed<A> -> c.annotated(predicate) }

/** The record's offset goes on the last element; a record that expands to none commits as a filtered one. */
fun <E, A : Any, B : Any> Stream<E, Committed<A>>.mapConcatRecord(f: (A) -> Iterable<B>): Stream<E, Committed<B>> =
    mapConcat { c: Committed<A> ->
        val elements = c.annotated(f).toList()
        elements.mapIndexed { i, b -> Committed(b, c.position, c.handle.takeIf { i == elements.lastIndex }) }
    }

@JvmName("mapRecordOrFailDeclaring")
fun <F, A : Any, B : Any> Stream<Nothing, Committed<A>>.mapRecordOrFail(
    f: Failing<F>.(A) -> B,
): Stream<F, Committed<B>> =
    mapOrFail<F, Committed<A>, Committed<B>> { c -> c.carrying(c.annotated { f(this, it) }) }

fun <E, A : Any, B : Any> Stream<E, Committed<A>>.mapRecordOrFail(f: Failing<E>.(A) -> B): Stream<E, Committed<B>> =
    mapOrFail<E, Committed<A>, Committed<B>> { c -> c.carrying(c.annotated { f(this, it) }) }

fun <E, A : Any, B : Any> Stream<E, Committed<A>>.mapParRecord(
    parallelism: Int,
    f: Fail<E>.(A) -> B,
): Stream<E, Committed<B>> =
    mapPar(parallelism) { c: Committed<A> -> c.carrying(c.annotated { f(this, it) }) }

/**
 * [mapParRecord] in order only within each partition: a slow record holds back the records after it on its own
 * partition, and no other's (spec 0156). Each partition's offsets still reach the end in order, so what is
 * committed is never past a record still being handled.
 */
fun <E, A : Any, B : Any> Stream<E, Committed<A>>.mapParPartitioned(
    parallelism: Int,
    f: Fail<E>.(A) -> B,
): Stream<E, Committed<B>> =
    mapParKeyed(parallelism, key = { c: Committed<A> -> c.position.topic to c.position.partition }) { c ->
        c.carrying(c.annotated { f(this, it) })
    }

@JvmName("mapParRecordOrFailDeclaring")
fun <F, A : Any, B : Any> Stream<Nothing, Committed<A>>.mapParRecordOrFail(
    parallelism: Int,
    f: Fail<F>.(A) -> B,
): Stream<F, Committed<B>> =
    mapParOrFail<F, Committed<A>, Committed<B>>(parallelism) { c -> c.carrying(c.annotated { f(this, it) }) }

fun <E, A : Any, B : Any> Stream<E, Committed<A>>.mapParRecordOrFail(
    parallelism: Int,
    f: Fail<E>.(A) -> B,
): Stream<E, Committed<B>> =
    mapParOrFail<E, Committed<A>, Committed<B>>(parallelism) { c -> c.carrying(c.annotated { f(this, it) }) }

/**
 * Up to [n] records, or what came within [within], as one element committed as a whole: each record in it is
 * marked handled, so each partition's offset moves to the last of its records. A record still expanding into
 * elements is committed with the batch its last element is in. A body on a batch runs outside any one record's
 * position and trace.
 */
fun <E, A : Any> Stream<E, Committed<A>>.groupedRecord(n: Int, within: Duration): Stream<E, Committed<List<A>>> =
    groupedWithin(n, within).map { taken: List<Committed<A>> ->
        Committed(taken.map { it.value }, taken.first().position, Batched(taken))
    }
