package dev.surefold.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.javadsl.Flow

fun <In, Out : Any> Pipe.Companion.from(flow: Flow<In, Out, NotUsed>): Pipe<Nothing, In, Out> =
    Pipe(Node.Stage(Node.Hole, flow, Pekko, "Pipe.from", pekkoSite()))

/** The way out to Pekko, open only once nothing is left that a graph would not understand. */
fun <In, Out : Any> Pipe<Nothing, In, Out>.toFlow(): Flow<In, Out, NotUsed> = flow
