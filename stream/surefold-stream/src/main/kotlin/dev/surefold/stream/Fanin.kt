package dev.surefold.stream

/**
 * Both feeds as one, in whatever order they arrive. `Stream` is covariant in its failure, so two feeds that fail
 * differently merge under the failure they share, named once at the call site.
 */
fun <E, E2 : E, A : Any> Stream<E, A>.merge(other: Stream<E2, A>): Stream<E, A> = Stream(Node.Merge(node, other.node))

/** [merge] over as many as there are, which is the shape a fan-in of feeds actually has. */
fun <E, E2 : E, A : Any> Stream<E, A>.mergeAll(vararg others: Stream<E2, A>): Stream<E, A> =
    others.fold(this) { merged, next -> merged.merge(next) }

/** Both feeds as one, [segmentSize] elements at a time from each in turn. */
fun <E, E2 : E, A : Any> Stream<E, A>.interleave(other: Stream<E2, A>, segmentSize: Int): Stream<E, A> =
    Stream(Node.Interleave(node, other.node, segmentSize))

/** One element from each, combined. Ends when either side does. */
fun <E, E2 : E, A : Any, B : Any, C : Any> Stream<E, A>.zipWith(
    other: Stream<E2, B>,
    f: (A, B) -> C,
): Stream<E, C> = Stream(Node.ZipWith(node, other.node, f.erased(), buildSite()))

/** [zipWith] into Kotlin's [Pair], which destructures, rather than the `japi.Pair` Pekko answers with. */
fun <E, E2 : E, A : Any, B : Any> Stream<E, A>.zip(other: Stream<E2, B>): Stream<E, Pair<A, B>> = zipWith(other, ::Pair)
