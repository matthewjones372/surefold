package dev.surefold.stream

import org.apache.pekko.stream.javadsl.Flow
import org.apache.pekko.stream.javadsl.Sink

/** Every element to [to] as well as downstream; [to]'s backpressure is the pipeline's. */
fun <A : Any> Pipe.Companion.alsoTo(to: Sink<A, *>): Pipe<Nothing, A, A> =
    Pipe(Node.Stage(Node.Hole, Flow.create<A>().alsoTo(to), Pekko, "alsoTo", pekkoSite()))

fun <E, In, Out : Any> Pipe<E, In, Out>.alsoTo(to: Sink<Out, *>): Pipe<E, In, Out> = via(Pipe.alsoTo(to))

fun <E, A : Any> Stream<E, A>.alsoTo(to: Sink<A, *>): Stream<E, A> = via(Pipe.alsoTo(to))

/** As [alsoTo], except that [to] is dropped from rather than allowed to slow the pipeline. */
fun <A : Any> Pipe.Companion.wireTap(to: Sink<A, *>): Pipe<Nothing, A, A> =
    Pipe(Node.Stage(Node.Hole, Flow.create<A>().wireTap(to), Pekko, "wireTap", pekkoSite()))

fun <E, In, Out : Any> Pipe<E, In, Out>.wireTap(to: Sink<Out, *>): Pipe<E, In, Out> = via(Pipe.wireTap(to))

fun <E, A : Any> Stream<E, A>.wireTap(to: Sink<A, *>): Stream<E, A> = via(Pipe.wireTap(to))
