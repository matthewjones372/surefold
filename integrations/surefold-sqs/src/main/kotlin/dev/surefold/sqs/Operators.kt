package dev.surefold.sqs

import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.fold
import dev.surefold.fail.onLeft
import dev.surefold.stream.Failing
import dev.surefold.stream.Stream
import dev.surefold.stream.map
import dev.surefold.stream.mapConcat
import dev.surefold.stream.mapOrFail
import dev.surefold.stream.mapPar

// surefold-stream's operators with the body on the value and the receipt carried through, named apart from them as
// surefold-kafka's are, so that with both packages imported `map` is not ambiguous.

fun <E, A : Any, B : Any> Stream<E, Received<A>>.mapMessage(f: (A) -> B): Stream<E, Received<B>> =
    map { r: Received<A> -> r.carrying(f(r.value)) }

@JvmName("mapMessageOrFailDeclaring")
fun <F, A : Any, B : Any> Stream<Nothing, Received<A>>.mapMessageOrFail(
    f: Failing<F>.(A) -> B,
): Stream<F, Received<B>> =
    mapOrFail<F, Received<A>, Received<B>> { r -> r.carrying(f(this, r.value)) }

fun <E, A : Any, B : Any> Stream<E, Received<A>>.mapMessageOrFail(f: Failing<E>.(A) -> B): Stream<E, Received<B>> =
    mapOrFail<E, Received<A>, Received<B>> { r -> r.carrying(f(this, r.value)) }

fun <E, A : Any, B : Any> Stream<E, Received<A>>.mapParMessage(
    parallelism: Int,
    f: Fail<E>.(A) -> B,
): Stream<E, Received<B>> =
    mapPar(parallelism) { r: Received<A> -> r.carrying(f(this, r.value)) }

/**
 * Each `Left` handed to [to], one at a time on a virtual thread and in order. Its message is not deleted: it comes
 * back after the visibility timeout, or at once with [nack], and the queue's redrive policy decides when it stops.
 * A throw from [to] is a defect.
 */
fun <E, L : Any, R : Any> Stream<E, Received<Either<L, R>>>.divertLefts(
    nack: Boolean = false,
    to: (L) -> Unit,
): Stream<E, Received<R>> =
    mapPar(1) { r: Received<Either<L, R>> ->
        r.also { it.value.onLeft { left -> to(left).also { r.receipt.dropped(nack) } } }
    }
        .mapConcat { r -> r.value.fold({ emptyList() }, { right -> listOf(r.carrying(right)) }) }

/** The first `Left` ends the run as `Failed`, with what came before it deleted and its own message left. */
fun <E, L : E, R : Any> Stream<E, Received<Either<L, R>>>.absolve(): Stream<E, Received<R>> =
    mapOrFail<E, Received<Either<L, R>>, Received<R>> { r -> r.carrying(r.value.fold({ fail(it) }, { it })) }
