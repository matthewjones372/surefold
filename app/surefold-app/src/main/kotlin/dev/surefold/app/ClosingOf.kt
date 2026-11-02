package dev.surefold.app

/**
 * Something `AutoCloseable` as a node, closed on release: `singleOf(create) { it.close() }` (spec 0133). Written
 * `closingOf { -> Pool() }` or `closingOf<Pool> { Pool() }`, because a bare lambda is ambiguous.
 */
inline fun <reified A : AutoCloseable> closingOf(noinline create: () -> A): Module =
    singleOf(create) { held -> held.close() }

/** A constructor that takes one dependency, closed on release. */
inline fun <
    reified A : AutoCloseable,
    reified D1 : Any,
    > closingOf(
    noinline create: (D1) -> A,
): Module = singleOf(create) { held -> held.close() }

/** As the one-dependency [closingOf], with 2. */
inline fun <
    reified A : AutoCloseable,
    reified D1 : Any,
    reified D2 : Any,
    > closingOf(
    noinline create: (D1, D2) -> A,
): Module = singleOf(create) { held -> held.close() }

/** As the one-dependency [closingOf], with 3. */
inline fun <
    reified A : AutoCloseable,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    > closingOf(
    noinline create: (D1, D2, D3) -> A,
): Module = singleOf(create) { held -> held.close() }

/** As the one-dependency [closingOf], with 4. */
inline fun <
    reified A : AutoCloseable,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    reified D4 : Any,
    > closingOf(
    noinline create: (D1, D2, D3, D4) -> A,
): Module = singleOf(create) { held -> held.close() }

/** As the one-dependency [closingOf], with 5. */
inline fun <
    reified A : AutoCloseable,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    reified D4 : Any,
    reified D5 : Any,
    > closingOf(
    noinline create: (D1, D2, D3, D4, D5) -> A,
): Module = singleOf(create) { held -> held.close() }

/** As the one-dependency [closingOf], with 6. */
inline fun <
    reified A : AutoCloseable,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    reified D4 : Any,
    reified D5 : Any,
    reified D6 : Any,
    > closingOf(
    noinline create: (D1, D2, D3, D4, D5, D6) -> A,
): Module = singleOf(create) { held -> held.close() }

/** As the one-dependency [closingOf], with 7. */
inline fun <
    reified A : AutoCloseable,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    reified D4 : Any,
    reified D5 : Any,
    reified D6 : Any,
    reified D7 : Any,
    > closingOf(
    noinline create: (D1, D2, D3, D4, D5, D6, D7) -> A,
): Module = singleOf(create) { held -> held.close() }

/** As the one-dependency [closingOf], with 8. */
inline fun <
    reified A : AutoCloseable,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    reified D4 : Any,
    reified D5 : Any,
    reified D6 : Any,
    reified D7 : Any,
    reified D8 : Any,
    > closingOf(
    noinline create: (D1, D2, D3, D4, D5, D6, D7, D8) -> A,
): Module = singleOf(create) { held -> held.close() }

/** As the one-dependency [closingOf], with 9. */
inline fun <
    reified A : AutoCloseable,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    reified D4 : Any,
    reified D5 : Any,
    reified D6 : Any,
    reified D7 : Any,
    reified D8 : Any,
    reified D9 : Any,
    > closingOf(
    noinline create: (D1, D2, D3, D4, D5, D6, D7, D8, D9) -> A,
): Module = singleOf(create) { held -> held.close() }
