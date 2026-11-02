package dev.surefold.app

import kotlin.reflect.typeOf

/** Its type argument is written out: a bare lambda also fits the one-dependency overload, as its `it`. */
inline fun <reified A : Any> single(noinline build: Wiring.() -> A): Module =
    module(typeOf<A>(), emptyList()) { build() }

/** A recipe, with what it needs as its parameters. */
inline fun <
    reified A : Any,
    reified D1 : Any,
    > single(
    noinline build: Wiring.(D1) -> A,
): Module = module(
    typeOf<A>(),
    listOf(typeOf<D1>()),
) { deps ->
    build(deps[0] as D1)
}

inline fun <
    reified A : Any,
    reified D1 : Any,
    reified D2 : Any,
    > single(
    noinline build: Wiring.(D1, D2) -> A,
): Module = module(
    typeOf<A>(),
    listOf(typeOf<D1>(), typeOf<D2>()),
) { deps ->
    build(deps[0] as D1, deps[1] as D2)
}

inline fun <
    reified A : Any,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    > single(
    noinline build: Wiring.(D1, D2, D3) -> A,
): Module = module(
    typeOf<A>(),
    listOf(typeOf<D1>(), typeOf<D2>(), typeOf<D3>()),
) { deps ->
    build(deps[0] as D1, deps[1] as D2, deps[2] as D3)
}

inline fun <
    reified A : Any,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    reified D4 : Any,
    > single(
    noinline build: Wiring.(D1, D2, D3, D4) -> A,
): Module = module(
    typeOf<A>(),
    listOf(typeOf<D1>(), typeOf<D2>(), typeOf<D3>(), typeOf<D4>()),
) { deps ->
    build(deps[0] as D1, deps[1] as D2, deps[2] as D3, deps[3] as D4)
}

inline fun <
    reified A : Any,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    reified D4 : Any,
    reified D5 : Any,
    > single(
    noinline build: Wiring.(D1, D2, D3, D4, D5) -> A,
): Module = module(
    typeOf<A>(),
    listOf(typeOf<D1>(), typeOf<D2>(), typeOf<D3>(), typeOf<D4>(), typeOf<D5>()),
) { deps ->
    build(deps[0] as D1, deps[1] as D2, deps[2] as D3, deps[3] as D4, deps[4] as D5)
}

inline fun <
    reified A : Any,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    reified D4 : Any,
    reified D5 : Any,
    reified D6 : Any,
    > single(
    noinline build: Wiring.(D1, D2, D3, D4, D5, D6) -> A,
): Module = module(
    typeOf<A>(),
    listOf(typeOf<D1>(), typeOf<D2>(), typeOf<D3>(), typeOf<D4>(), typeOf<D5>(), typeOf<D6>()),
) { deps ->
    build(deps[0] as D1, deps[1] as D2, deps[2] as D3, deps[3] as D4, deps[4] as D5, deps[5] as D6)
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
    > single(
    noinline build: Wiring.(D1, D2, D3, D4, D5, D6, D7) -> A,
): Module = module(
    typeOf<A>(),
    listOf(typeOf<D1>(), typeOf<D2>(), typeOf<D3>(), typeOf<D4>(), typeOf<D5>(), typeOf<D6>(), typeOf<D7>()),
) { deps ->
    build(deps[0] as D1, deps[1] as D2, deps[2] as D3, deps[3] as D4, deps[4] as D5, deps[5] as D6, deps[6] as D7)
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
    > single(
    noinline build: Wiring.(D1, D2, D3, D4, D5, D6, D7, D8) -> A,
): Module = module(
    typeOf<A>(),
    listOf(
        typeOf<D1>(),
        typeOf<D2>(),
        typeOf<D3>(),
        typeOf<D4>(),
        typeOf<D5>(),
        typeOf<D6>(),
        typeOf<D7>(),
        typeOf<D8>(),
    ),
) { deps ->
    build(
        deps[0] as D1,
        deps[1] as D2,
        deps[2] as D3,
        deps[3] as D4,
        deps[4] as D5,
        deps[5] as D6,
        deps[6] as D7,
        deps[7] as D8,
    )
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
    > single(
    noinline build: Wiring.(D1, D2, D3, D4, D5, D6, D7, D8, D9) -> A,
): Module = module(
    typeOf<A>(),
    listOf(
        typeOf<D1>(),
        typeOf<D2>(),
        typeOf<D3>(),
        typeOf<D4>(),
        typeOf<D5>(),
        typeOf<D6>(),
        typeOf<D7>(),
        typeOf<D8>(),
        typeOf<D9>(),
    ),
) { deps ->
    build(
        deps[0] as D1,
        deps[1] as D2,
        deps[2] as D3,
        deps[3] as D4,
        deps[4] as D5,
        deps[5] as D6,
        deps[6] as D7,
        deps[7] as D8,
        deps[8] as D9,
    )
}
