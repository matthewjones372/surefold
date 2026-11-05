package dev.surefold

import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.FailAll
import dev.surefold.fail.NonEmptyList
import dev.surefold.fail.attempt
import dev.surefold.fail.getOrElse
import dev.surefold.fail.nonEmptyListOf
import dev.surefold.fail.toNonEmptyListOrNull

/**
 * Runs both branches in order on the calling thread and keeps every failure: a fail ends only its own branch, so both
 * run and their errors answer together, in branch order. A throw is not kept; it ends the call where it happens.
 */
fun <E, A, B, C> Fail<NonEmptyList<E>>.zipOrAccumulate(
    fa: FailAll<E>.() -> A,
    fb: FailAll<E>.() -> B,
    transform: (A, B) -> C,
): C {
    val a = branch(fa)
    val b = branch(fb)
    failEvery(a, b)
    return transform(a.settled(), b.settled())
}

/** The same two branches, their failures folded with [combine] into the error this scope declares. */
fun <E, A, B, C> Fail<E>.zipOrAccumulate(
    combine: (E, E) -> E,
    fa: FailAll<E>.() -> A,
    fb: FailAll<E>.() -> B,
    transform: (A, B) -> C,
): C = combined(combine) { zipOrAccumulate(fa, fb, transform) }

/** Three branches, in order. */
fun <E, A, B, C, D> Fail<NonEmptyList<E>>.zipOrAccumulate(
    fa: FailAll<E>.() -> A,
    fb: FailAll<E>.() -> B,
    fc: FailAll<E>.() -> C,
    transform: (A, B, C) -> D,
): D {
    val a = branch(fa)
    val b = branch(fb)
    val c = branch(fc)
    failEvery(a, b, c)
    return transform(a.settled(), b.settled(), c.settled())
}

/** Three branches, their failures folded with [combine]. */
fun <E, A, B, C, D> Fail<E>.zipOrAccumulate(
    combine: (E, E) -> E,
    fa: FailAll<E>.() -> A,
    fb: FailAll<E>.() -> B,
    fc: FailAll<E>.() -> C,
    transform: (A, B, C) -> D,
): D = combined(combine) { zipOrAccumulate(fa, fb, fc, transform) }

/** Four branches, in order. */
fun <E, A, B, C, D, F> Fail<NonEmptyList<E>>.zipOrAccumulate(
    fa: FailAll<E>.() -> A,
    fb: FailAll<E>.() -> B,
    fc: FailAll<E>.() -> C,
    fd: FailAll<E>.() -> D,
    transform: (A, B, C, D) -> F,
): F {
    val a = branch(fa)
    val b = branch(fb)
    val c = branch(fc)
    val d = branch(fd)
    failEvery(a, b, c, d)
    return transform(a.settled(), b.settled(), c.settled(), d.settled())
}

/** Four branches, their failures folded with [combine]. */
fun <E, A, B, C, D, F> Fail<E>.zipOrAccumulate(
    combine: (E, E) -> E,
    fa: FailAll<E>.() -> A,
    fb: FailAll<E>.() -> B,
    fc: FailAll<E>.() -> C,
    fd: FailAll<E>.() -> D,
    transform: (A, B, C, D) -> F,
): F = combined(combine) { zipOrAccumulate(fa, fb, fc, fd, transform) }

/** Five branches, in order. */
fun <E, A, B, C, D, F, G> Fail<NonEmptyList<E>>.zipOrAccumulate(
    fa: FailAll<E>.() -> A,
    fb: FailAll<E>.() -> B,
    fc: FailAll<E>.() -> C,
    fd: FailAll<E>.() -> D,
    fe: FailAll<E>.() -> F,
    transform: (A, B, C, D, F) -> G,
): G {
    val a = branch(fa)
    val b = branch(fb)
    val c = branch(fc)
    val d = branch(fd)
    val e = branch(fe)
    failEvery(a, b, c, d, e)
    return transform(a.settled(), b.settled(), c.settled(), d.settled(), e.settled())
}

/** Five branches, their failures folded with [combine]. */
fun <E, A, B, C, D, F, G> Fail<E>.zipOrAccumulate(
    combine: (E, E) -> E,
    fa: FailAll<E>.() -> A,
    fb: FailAll<E>.() -> B,
    fc: FailAll<E>.() -> C,
    fd: FailAll<E>.() -> D,
    fe: FailAll<E>.() -> F,
    transform: (A, B, C, D, F) -> G,
): G = combined(combine) { zipOrAccumulate(fa, fb, fc, fd, fe, transform) }

/** Six branches, in order. */
fun <E, A, B, C, D, F, G, H> Fail<NonEmptyList<E>>.zipOrAccumulate(
    fa: FailAll<E>.() -> A,
    fb: FailAll<E>.() -> B,
    fc: FailAll<E>.() -> C,
    fd: FailAll<E>.() -> D,
    fe: FailAll<E>.() -> F,
    ff: FailAll<E>.() -> G,
    transform: (A, B, C, D, F, G) -> H,
): H {
    val a = branch(fa)
    val b = branch(fb)
    val c = branch(fc)
    val d = branch(fd)
    val e = branch(fe)
    val f = branch(ff)
    failEvery(a, b, c, d, e, f)
    return transform(
        a.settled(),
        b.settled(),
        c.settled(),
        d.settled(),
        e.settled(),
        f.settled(),
    )
}

/** Six branches, their failures folded with [combine]. */
fun <E, A, B, C, D, F, G, H> Fail<E>.zipOrAccumulate(
    combine: (E, E) -> E,
    fa: FailAll<E>.() -> A,
    fb: FailAll<E>.() -> B,
    fc: FailAll<E>.() -> C,
    fd: FailAll<E>.() -> D,
    fe: FailAll<E>.() -> F,
    ff: FailAll<E>.() -> G,
    transform: (A, B, C, D, F, G) -> H,
): H = combined(combine) { zipOrAccumulate(fa, fb, fc, fd, fe, ff, transform) }

/** Seven branches, in order. */
fun <E, A, B, C, D, F, G, H, I> Fail<NonEmptyList<E>>.zipOrAccumulate(
    fa: FailAll<E>.() -> A,
    fb: FailAll<E>.() -> B,
    fc: FailAll<E>.() -> C,
    fd: FailAll<E>.() -> D,
    fe: FailAll<E>.() -> F,
    ff: FailAll<E>.() -> G,
    fg: FailAll<E>.() -> H,
    transform: (A, B, C, D, F, G, H) -> I,
): I {
    val a = branch(fa)
    val b = branch(fb)
    val c = branch(fc)
    val d = branch(fd)
    val e = branch(fe)
    val f = branch(ff)
    val g = branch(fg)
    failEvery(a, b, c, d, e, f, g)
    return transform(
        a.settled(),
        b.settled(),
        c.settled(),
        d.settled(),
        e.settled(),
        f.settled(),
        g.settled(),
    )
}

/** Seven branches, their failures folded with [combine]. */
fun <E, A, B, C, D, F, G, H, I> Fail<E>.zipOrAccumulate(
    combine: (E, E) -> E,
    fa: FailAll<E>.() -> A,
    fb: FailAll<E>.() -> B,
    fc: FailAll<E>.() -> C,
    fd: FailAll<E>.() -> D,
    fe: FailAll<E>.() -> F,
    ff: FailAll<E>.() -> G,
    fg: FailAll<E>.() -> H,
    transform: (A, B, C, D, F, G, H) -> I,
): I = combined(combine) { zipOrAccumulate(fa, fb, fc, fd, fe, ff, fg, transform) }

/** Eight branches, in order. */
fun <E, A, B, C, D, F, G, H, I, J> Fail<NonEmptyList<E>>.zipOrAccumulate(
    fa: FailAll<E>.() -> A,
    fb: FailAll<E>.() -> B,
    fc: FailAll<E>.() -> C,
    fd: FailAll<E>.() -> D,
    fe: FailAll<E>.() -> F,
    ff: FailAll<E>.() -> G,
    fg: FailAll<E>.() -> H,
    fh: FailAll<E>.() -> I,
    transform: (A, B, C, D, F, G, H, I) -> J,
): J {
    val a = branch(fa)
    val b = branch(fb)
    val c = branch(fc)
    val d = branch(fd)
    val e = branch(fe)
    val f = branch(ff)
    val g = branch(fg)
    val h = branch(fh)
    failEvery(a, b, c, d, e, f, g, h)
    return transform(
        a.settled(),
        b.settled(),
        c.settled(),
        d.settled(),
        e.settled(),
        f.settled(),
        g.settled(),
        h.settled(),
    )
}

/** Eight branches, their failures folded with [combine]. */
fun <E, A, B, C, D, F, G, H, I, J> Fail<E>.zipOrAccumulate(
    combine: (E, E) -> E,
    fa: FailAll<E>.() -> A,
    fb: FailAll<E>.() -> B,
    fc: FailAll<E>.() -> C,
    fd: FailAll<E>.() -> D,
    fe: FailAll<E>.() -> F,
    ff: FailAll<E>.() -> G,
    fg: FailAll<E>.() -> H,
    fh: FailAll<E>.() -> I,
    transform: (A, B, C, D, F, G, H, I) -> J,
): J = combined(combine) { zipOrAccumulate(fa, fb, fc, fd, fe, ff, fg, fh, transform) }

/** Nine branches, in order. */
fun <E, A, B, C, D, F, G, H, I, J, K> Fail<NonEmptyList<E>>.zipOrAccumulate(
    fa: FailAll<E>.() -> A,
    fb: FailAll<E>.() -> B,
    fc: FailAll<E>.() -> C,
    fd: FailAll<E>.() -> D,
    fe: FailAll<E>.() -> F,
    ff: FailAll<E>.() -> G,
    fg: FailAll<E>.() -> H,
    fh: FailAll<E>.() -> I,
    fi: FailAll<E>.() -> J,
    transform: (A, B, C, D, F, G, H, I, J) -> K,
): K {
    val a = branch(fa)
    val b = branch(fb)
    val c = branch(fc)
    val d = branch(fd)
    val e = branch(fe)
    val f = branch(ff)
    val g = branch(fg)
    val h = branch(fh)
    val i = branch(fi)
    failEvery(a, b, c, d, e, f, g, h, i)
    return transform(
        a.settled(),
        b.settled(),
        c.settled(),
        d.settled(),
        e.settled(),
        f.settled(),
        g.settled(),
        h.settled(),
        i.settled(),
    )
}

/** Nine branches, their failures folded with [combine]. */
fun <E, A, B, C, D, F, G, H, I, J, K> Fail<E>.zipOrAccumulate(
    combine: (E, E) -> E,
    fa: FailAll<E>.() -> A,
    fb: FailAll<E>.() -> B,
    fc: FailAll<E>.() -> C,
    fd: FailAll<E>.() -> D,
    fe: FailAll<E>.() -> F,
    ff: FailAll<E>.() -> G,
    fg: FailAll<E>.() -> H,
    fh: FailAll<E>.() -> I,
    fi: FailAll<E>.() -> J,
    transform: (A, B, C, D, F, G, H, I, J) -> K,
): K = combined(combine) { zipOrAccumulate(fa, fb, fc, fd, fe, ff, fg, fh, fi, transform) }

/**
 * Runs [transform] on each element in order on the calling thread and keeps every failure: a fail ends only its own
 * element, so every element runs and their errors answer together, in the input's order.
 */
fun <E, A, B> Fail<NonEmptyList<E>>.mapOrAccumulate(iterable: Iterable<A>, transform: FailAll<E>.(A) -> B): List<B> {
    val results = iterable.map { element -> branch { transform(element) } }
    failEvery(results)
    return results.map { it.settled() }
}

/** The same map, its failures folded with [combine] into the error this scope declares. */
fun <E, A, B> Fail<E>.mapOrAccumulate(
    combine: (E, E) -> E,
    iterable: Iterable<A>,
    transform: FailAll<E>.(A) -> B,
): List<B> = combined(combine) { mapOrAccumulate(iterable, transform) }

/** Runs [block] on a scope that fails into a list, so a branch can fail with one error or with several. */
private fun <E, A> branch(block: FailAll<E>.() -> A): Either<NonEmptyList<E>, A> = attempt { Branch(this).block() }

/** A branch's scope: its errors go into the attempt [branch] opened, which also refuses a leaked or foreign fail. */
private class Branch<E>(private val all: Fail<NonEmptyList<E>>) : FailAll<E> {
    override fun fail(error: E): Nothing = all.fail(nonEmptyListOf(error))

    override fun failAll(errors: NonEmptyList<E>): Nothing = all.fail(errors)
}

/** Fails with every branch's errors together, or returns so the caller can read their values. */
private fun <E> Fail<NonEmptyList<E>>.failEvery(vararg branches: Either<NonEmptyList<E>, *>) =
    failEvery(branches.asList())

/** The same, for branches already in a list. */
private fun <E> Fail<NonEmptyList<E>>.failEvery(branches: List<Either<NonEmptyList<E>, *>>) {
    branches.mapNotNull { it as? Either.Left }.flatMap { it.value }.toNonEmptyListOrNull()?.let { fail(it) }
}

/** The value of a branch that [failEvery] has already seen succeed. */
private fun <A> Either<*, A>.settled(): A = (this as Either.Right<A>).value

/** Runs [block] collecting into a list, then fails this scope with that list folded by [combine]. */
private fun <E, A> Fail<E>.combined(combine: (E, E) -> E, block: Fail<NonEmptyList<E>>.() -> A): A =
    attempt(block).getOrElse { fail(it.reduce(combine)) }
