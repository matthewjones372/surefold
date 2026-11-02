package dev.surefold.stream

import java.util.concurrent.atomic.AtomicReference
/** A stream and the sink that ends it, described. Nothing runs until [run] or [start] names a system. */
class Run<out E, out R> @StreamSpi constructor(
    @property:StreamSpi val node: Node,
    @property:StreamSpi val end: End,
) {

    /** What a backend compiled the run to, kept: every run materialises the same blueprint. */
    @property:StreamSpi
    val compiled = CompileCache()

    /** The last backend this run was checked against and passed: a description does not change between runs. */
    internal val admitted = AtomicReference<BackendKey?>(null)
}

fun <E, A : Any> Stream<E, A>.runCollect(): Run<E, List<A>> = Run(node, End.Collect)

fun <E, A : Any, R : Any> Stream<E, A>.runFold(zero: R, f: (R, A) -> R): Run<E, R> =
    Run(node, End.Fold(zero, f.erased(), buildSite()))
