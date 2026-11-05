package dev.surefold.app

/** A constructor that takes nothing. Its own type argument, because a bare lambda is ambiguous. */
inline fun <reified A : Any> singleOf(noinline create: () -> A): Module = single<A> { create() }

/**
 * A constructor as a node: what it returns is the key, and what it takes are the dependencies.
 *
 * A constructor reference already declares both, and writing them out again as lambda parameters is the line a graph
 * costs over a call. `singleOf(::PgUserRepo)` is `single { ds: DataSource, log: Log -> PgUserRepo(ds, log) }`.
 */
inline fun <
    reified A : Any,
    reified D1 : Any,
> singleOf(
    noinline create: (D1) -> A,
): Module = single { d1: D1 -> create(d1) }

inline fun <
    reified A : Any,
    reified D1 : Any,
    reified D2 : Any,
> singleOf(
    noinline create: (D1, D2) -> A,
): Module = single { d1: D1, d2: D2 -> create(d1, d2) }

inline fun <
    reified A : Any,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
> singleOf(
    noinline create: (D1, D2, D3) -> A,
): Module = single { d1: D1, d2: D2, d3: D3 -> create(d1, d2, d3) }

inline fun <
    reified A : Any,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    reified D4 : Any,
> singleOf(
    noinline create: (D1, D2, D3, D4) -> A,
): Module = single { d1: D1, d2: D2, d3: D3, d4: D4 -> create(d1, d2, d3, d4) }

inline fun <
    reified A : Any,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    reified D4 : Any,
    reified D5 : Any,
> singleOf(
    noinline create: (D1, D2, D3, D4, D5) -> A,
): Module = single { d1: D1, d2: D2, d3: D3, d4: D4, d5: D5 -> create(d1, d2, d3, d4, d5) }

inline fun <
    reified A : Any,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    reified D4 : Any,
    reified D5 : Any,
    reified D6 : Any,
> singleOf(
    noinline create: (D1, D2, D3, D4, D5, D6) -> A,
): Module = single { d1: D1, d2: D2, d3: D3, d4: D4, d5: D5, d6: D6 -> create(d1, d2, d3, d4, d5, d6) }

inline fun <
    reified A : Any,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    reified D4 : Any,
    reified D5 : Any,
    reified D6 : Any,
    reified D7 : Any,
> singleOf(
    noinline create: (D1, D2, D3, D4, D5, D6, D7) -> A,
): Module = single { d1: D1, d2: D2, d3: D3, d4: D4, d5: D5, d6: D6, d7: D7 -> create(d1, d2, d3, d4, d5, d6, d7) }

inline fun <
    reified A : Any,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    reified D4 : Any,
    reified D5 : Any,
    reified D6 : Any,
    reified D7 : Any,
    reified D8 : Any,
> singleOf(
    noinline create: (D1, D2, D3, D4, D5, D6, D7, D8) -> A,
): Module = single { d1: D1, d2: D2, d3: D3, d4: D4, d5: D5, d6: D6, d7: D7, d8: D8 ->
    create(d1, d2, d3, d4, d5, d6, d7, d8)
}

inline fun <
    reified A : Any,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    reified D4 : Any,
    reified D5 : Any,
    reified D6 : Any,
    reified D7 : Any,
    reified D8 : Any,
    reified D9 : Any,
> singleOf(
    noinline create: (D1, D2, D3, D4, D5, D6, D7, D8, D9) -> A,
): Module = single { d1: D1, d2: D2, d3: D3, d4: D4, d5: D5, d6: D6, d7: D7, d8: D8, d9: D9 ->
    create(d1, d2, d3, d4, d5, d6, d7, d8, d9)
}

inline fun <reified A : Any> singleOf(
    noinline create: () -> A,
    noinline release: (A) -> Unit,
): Module = single<A> { install({ create() }) { held, _ -> release(held) } }

/**
 * A constructor and how what it built is given back.
 *
 * The shape most of a service's graph turns out to be: a pool, a client, a connection, each with a teardown that is
 * `close` or `dispose` or `shutdown` or `terminate`. `singleOf(::Hikari, Hikari::close)` is `single { … -> install({
 * Hikari(…) }) { held, _ -> held.close() } }`.
 */
inline fun <
    reified A : Any,
    reified D1 : Any,
> singleOf(
    noinline create: (D1) -> A,
    noinline release: (A) -> Unit,
): Module = single { d1: D1 ->
    install({ create(d1) }) { held, _ -> release(held) }
}

inline fun <
    reified A : Any,
    reified D1 : Any,
    reified D2 : Any,
> singleOf(
    noinline create: (D1, D2) -> A,
    noinline release: (A) -> Unit,
): Module = single { d1: D1, d2: D2 ->
    install({ create(d1, d2) }) { held, _ -> release(held) }
}

inline fun <
    reified A : Any,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
> singleOf(
    noinline create: (D1, D2, D3) -> A,
    noinline release: (A) -> Unit,
): Module = single { d1: D1, d2: D2, d3: D3 ->
    install({ create(d1, d2, d3) }) { held, _ -> release(held) }
}

inline fun <
    reified A : Any,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    reified D4 : Any,
> singleOf(
    noinline create: (D1, D2, D3, D4) -> A,
    noinline release: (A) -> Unit,
): Module = single { d1: D1, d2: D2, d3: D3, d4: D4 ->
    install({ create(d1, d2, d3, d4) }) { held, _ -> release(held) }
}

inline fun <
    reified A : Any,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    reified D4 : Any,
    reified D5 : Any,
> singleOf(
    noinline create: (D1, D2, D3, D4, D5) -> A,
    noinline release: (A) -> Unit,
): Module = single { d1: D1, d2: D2, d3: D3, d4: D4, d5: D5 ->
    install({ create(d1, d2, d3, d4, d5) }) { held, _ -> release(held) }
}

inline fun <
    reified A : Any,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    reified D4 : Any,
    reified D5 : Any,
    reified D6 : Any,
> singleOf(
    noinline create: (D1, D2, D3, D4, D5, D6) -> A,
    noinline release: (A) -> Unit,
): Module = single { d1: D1, d2: D2, d3: D3, d4: D4, d5: D5, d6: D6 ->
    install({ create(d1, d2, d3, d4, d5, d6) }) { held, _ -> release(held) }
}

inline fun <
    reified A : Any,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    reified D4 : Any,
    reified D5 : Any,
    reified D6 : Any,
    reified D7 : Any,
> singleOf(
    noinline create: (D1, D2, D3, D4, D5, D6, D7) -> A,
    noinline release: (A) -> Unit,
): Module = single { d1: D1, d2: D2, d3: D3, d4: D4, d5: D5, d6: D6, d7: D7 ->
    install({ create(d1, d2, d3, d4, d5, d6, d7) }) { held, _ -> release(held) }
}

inline fun <
    reified A : Any,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    reified D4 : Any,
    reified D5 : Any,
    reified D6 : Any,
    reified D7 : Any,
    reified D8 : Any,
> singleOf(
    noinline create: (D1, D2, D3, D4, D5, D6, D7, D8) -> A,
    noinline release: (A) -> Unit,
): Module = single { d1: D1, d2: D2, d3: D3, d4: D4, d5: D5, d6: D6, d7: D7, d8: D8 ->
    install({ create(d1, d2, d3, d4, d5, d6, d7, d8) }) { held, _ -> release(held) }
}

inline fun <
    reified A : Any,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    reified D4 : Any,
    reified D5 : Any,
    reified D6 : Any,
    reified D7 : Any,
    reified D8 : Any,
    reified D9 : Any,
> singleOf(
    noinline create: (D1, D2, D3, D4, D5, D6, D7, D8, D9) -> A,
    noinline release: (A) -> Unit,
): Module = single { d1: D1, d2: D2, d3: D3, d4: D4, d5: D5, d6: D6, d7: D7, d8: D8, d9: D9 ->
    install({ create(d1, d2, d3, d4, d5, d6, d7, d8, d9) }) { held, _ -> release(held) }
}
