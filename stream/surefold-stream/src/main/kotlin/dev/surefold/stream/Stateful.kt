package dev.surefold.stream

/** [zero] first, then what [f] carried after each element: a running total, one element at a time. */
fun <A : Any, S : Any> Pipe.Companion.scan(zero: S, f: (S, A) -> S): Pipe<Nothing, A, S> =
    Pipe(Node.Scan(Node.Hole, zero, f.erased(), buildSite()))

fun <E, In, Out : Any, S : Any> Pipe<E, In, Out>.scan(zero: S, f: (S, Out) -> S): Pipe<E, In, S> =
    via(Pipe.scan(zero, f))

fun <E, A : Any, S : Any> Stream<E, A>.scan(zero: S, f: (S, A) -> S): Stream<E, S> = via(Pipe.scan(zero, f))

/**
 * State carried across elements, as a Kotlin [Pair] of what to carry on with and what to emit. [onComplete] is the one
 * last element the state may still owe, or null where it owes none.
 */
fun <A : Any, S, B : Any> Pipe.Companion.statefulMap(
    create: () -> S,
    f: (S, A) -> Pair<S, B>,
    onComplete: (S) -> B? = { null },
): Pipe<Nothing, A, B> = Pipe(Node.StatefulMap(Node.Hole, create, f.erased(), onComplete.erased(), buildSite()))

fun <E, In, Out : Any, S, B : Any> Pipe<E, In, Out>.statefulMap(
    create: () -> S,
    f: (S, Out) -> Pair<S, B>,
    onComplete: (S) -> B? = { null },
): Pipe<E, In, B> = via(Pipe.statefulMap(create, f, onComplete))

fun <E, A : Any, S, B : Any> Stream<E, A>.statefulMap(
    create: () -> S,
    f: (S, A) -> Pair<S, B>,
    onComplete: (S) -> B? = { null },
): Stream<E, B> = via(Pipe.statefulMap(create, f, onComplete))
