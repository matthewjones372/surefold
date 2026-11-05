package dev.surefold.stream

/** The first [n] elements, and then the end of the stream. */
fun <A : Any> Pipe.Companion.take(n: Long): Pipe<Nothing, A, A> = Pipe(Node.Take(Node.Hole, n))

fun <E, In, Out : Any> Pipe<E, In, Out>.take(n: Long): Pipe<E, In, Out> = via(Pipe.take(n))

fun <E, A : Any> Stream<E, A>.take(n: Long): Stream<E, A> = via(Pipe.take(n))

/** Everything after the first [n] elements. */
fun <A : Any> Pipe.Companion.drop(n: Long): Pipe<Nothing, A, A> = Pipe(Node.Drop(Node.Hole, n))

fun <E, In, Out : Any> Pipe<E, In, Out>.drop(n: Long): Pipe<E, In, Out> = via(Pipe.drop(n))

fun <E, A : Any> Stream<E, A>.drop(n: Long): Stream<E, A> = via(Pipe.drop(n))

/** Elements until [predicate] first answers false, which is where the stream ends. */
fun <A : Any> Pipe.Companion.takeWhile(predicate: (A) -> Boolean): Pipe<Nothing, A, A> =
    Pipe(Node.TakeWhile(Node.Hole, predicate.erased(), buildSite()))

fun <E, In, Out : Any> Pipe<E, In, Out>.takeWhile(predicate: (Out) -> Boolean): Pipe<E, In, Out> =
    via(Pipe.takeWhile(predicate))

fun <E, A : Any> Stream<E, A>.takeWhile(predicate: (A) -> Boolean): Stream<E, A> = via(Pipe.takeWhile(predicate))

/** Elements from where [predicate] first answers false, that one included. */
fun <A : Any> Pipe.Companion.dropWhile(predicate: (A) -> Boolean): Pipe<Nothing, A, A> =
    Pipe(Node.DropWhile(Node.Hole, predicate.erased(), buildSite()))

fun <E, In, Out : Any> Pipe<E, In, Out>.dropWhile(predicate: (Out) -> Boolean): Pipe<E, In, Out> =
    via(Pipe.dropWhile(predicate))

fun <E, A : Any> Stream<E, A>.dropWhile(predicate: (A) -> Boolean): Stream<E, A> = via(Pipe.dropWhile(predicate))

/** The elements [predicate] answers false for, which is [filter]'s mirror and Pekko's own name. */
fun <A : Any> Pipe.Companion.filterNot(predicate: (A) -> Boolean): Pipe<Nothing, A, A> =
    Pipe(Node.FilterNot(Node.Hole, predicate.erased(), buildSite()))

fun <E, In, Out : Any> Pipe<E, In, Out>.filterNot(predicate: (Out) -> Boolean): Pipe<E, In, Out> =
    via(Pipe.filterNot(predicate))

fun <E, A : Any> Stream<E, A>.filterNot(predicate: (A) -> Boolean): Stream<E, A> = via(Pipe.filterNot(predicate))
