package dev.surefold.stream

import kotlin.time.Duration

/** Elements in batches of [n], the last one short where the stream ended inside it. */
fun <A : Any> Pipe.Companion.grouped(n: Int): Pipe<Nothing, A, List<A>> = Pipe(Node.Grouped(Node.Hole, n))

fun <E, In, Out : Any> Pipe<E, In, Out>.grouped(n: Int): Pipe<E, In, List<Out>> = via(Pipe.grouped(n))

fun <E, A : Any> Stream<E, A>.grouped(n: Int): Stream<E, List<A>> = via(Pipe.grouped(n))

/** A window of [n] elements, moved on by [step] each time. */
fun <A : Any> Pipe.Companion.sliding(n: Int, step: Int = 1): Pipe<Nothing, A, List<A>> =
    Pipe(Node.Sliding(Node.Hole, n, step))

fun <E, In, Out : Any> Pipe<E, In, Out>.sliding(n: Int, step: Int = 1): Pipe<E, In, List<Out>> =
    via(Pipe.sliding(n, step))

fun <E, A : Any> Stream<E, A>.sliding(n: Int, step: Int = 1): Stream<E, List<A>> = via(Pipe.sliding(n, step))

/** Batches of at most [n], and never later than [within], so a quiet feed still answers. */
fun <A : Any> Pipe.Companion.groupedWithin(n: Int, within: Duration): Pipe<Nothing, A, List<A>> =
    Pipe(Node.GroupedWithin(Node.Hole, n, within))

fun <E, In, Out : Any> Pipe<E, In, Out>.groupedWithin(n: Int, within: Duration): Pipe<E, In, List<Out>> =
    via(Pipe.groupedWithin(n, within))

fun <E, A : Any> Stream<E, A>.groupedWithin(n: Int, within: Duration): Stream<E, List<A>> =
    via(Pipe.groupedWithin(n, within))

/**
 * Lets everything upstream run ahead of what reads it by up to [size] elements, and no further: a slow stage below no
 * longer holds up a fast one above until the room is gone. Every backend backpressures when it is full; dropping
 * instead is Pekko's own `buffer(size, strategy)`.
 */
fun <A : Any> Pipe.Companion.buffer(size: Int): Pipe<Nothing, A, A> {
    require(size > 0) { "a buffer holds at least one element, and $size was asked for" }
    return Pipe(Node.Buffer(Node.Hole, size))
}

fun <E, In, Out : Any> Pipe<E, In, Out>.buffer(size: Int): Pipe<E, In, Out> = via(Pipe.buffer(size))

fun <E, A : Any> Stream<E, A>.buffer(size: Int): Stream<E, A> = via(Pipe.buffer(size))
