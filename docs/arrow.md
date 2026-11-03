# Coming from Arrow

**Code written with Arrow moves to Surefold with a few renames, and code that keeps
both runs through `surefold-arrow`.** Surefold's typed errors follow the model Arrow
made familiar in Kotlin, and its combinators keep the names and shapes of
`arrow-fx-coroutines`. Surefold's own types are written for blocking code on
virtual threads, so its core depends on nothing but the Kotlin standard library.

## The renames

| Arrow | Surefold |
|---|---|
| `either { }` | `attempt { }` |
| `raise(error)` | `fail(error)` |
| `Raise<E>` | `Fail<E>` |
| `context(_: Raise<E>)` | `context(_: Fail<E>)` |
| `ensure`, `ensureNotNull`, `bind`, `recover` | the same names, from `dev.surefold.fail` |
| `arrow.core.Either`, `NonEmptyList` | `dev.surefold.fail.Either`, `NonEmptyList` |
| `Option<A>` | `A?` |
| `Either.catch { }`, `Either.catchOrThrow<T, _> { }` | the same, from `dev.surefold.fail` |
| `parZip`, `parMap`, `raceN`, `parZipOrAccumulate`, `parMapOrAccumulate` | the same names in `dev.surefold`, without `suspend` |
| `zipOrAccumulate`, `mapOrAccumulate`, `accumulate`, `ensureOrAccumulate`, `bindOrAccumulate` | the same names, run in order on the calling thread; `accumulate` and its two checks are in `dev.surefold.fail`. `bindOrAccumulate` answers `A?` |

<!-- arrow-renames -->
```kotlin
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.parZip

data class Missing(val id: Int)

fun user(id: Int): Either<Missing, String> = attempt { if (id == 1) "ada" else fail(Missing(id)) }

fun pair(a: Int, b: Int): Either<Missing, Pair<String, String>> = attempt {
    parZip({ user(a).bind() }, { user(b).bind() }) { x, y -> x to y }
}
```

## Where Surefold differs

- **`catch (e: Exception)` cannot swallow a failure.** A `fail` leaves its block
  as a `Throwable` that is not an `Exception`, and `nonFatalOrThrow()` rethrows
  it. Arrow's `raise` is a `CancellationException`, which a broad `catch` and
  some libraries take for a cancellation.
- **A failure never crosses a thread.** Each fork fails into a scope of its own,
  and the combinator carries the failure back. A `fail` on a scope from a thread
  it did not fork, or after its `attempt` has returned, is refused with an error
  that names where the scope was opened.
- **No `Option`.** A nullable type says the same thing, and the compiler checks it.

## Using both

`surefold-arrow` adds `arrow-core` and nothing else:

| Call | What it does |
|---|---|
| `either.toArrow()`, `either.toSurefold()` | an `Either` from one library to the other |
| `nel.toArrow()`, `nel.toSurefold()` | the same for `NonEmptyList` |
| `raise.surefold { … }` | Surefold code inside an Arrow `Raise`: a `fail` in it raises there |
| `arrow { … }` inside a `Fail` | Arrow code inside Surefold: a `raise` in it fails there |

<!-- arrow-both -->
```kotlin
import arrow.core.raise.Raise
import arrow.core.raise.either
import dev.surefold.arrow.arrow
import dev.surefold.arrow.surefold
import dev.surefold.arrow.toArrow
import dev.surefold.arrow.toSurefold
import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import dev.surefold.fail.fail
import dev.surefold.parZip

data class NotFound(val id: Int)

// Moved to Surefold: the function asks its caller for a Fail.
context(_: Fail<NotFound>)
fun user(id: Int): String = if (id > 0) "user-$id" else fail(NotFound(id))

// Not moved yet: still on Arrow's Raise.
fun Raise<NotFound>.orders(id: Int): List<String> = if (id > 0) listOf("order-1") else raise(NotFound(id))

// Surefold calling Arrow: arrow { } fails the Surefold scope with what the Arrow code raised.
fun dashboard(id: Int): Either<NotFound, Pair<String, List<String>>> = attempt {
    parZip({ user(id) }, { arrow { orders(id) } }) { user, orders -> user to orders }
}

// Arrow calling Surefold: surefold { } raises into the Arrow scope what the Surefold code failed with.
fun name(id: Int): arrow.core.Either<NotFound, String> = either { surefold { user(id) } }

// An answer handed across as a value, either way.
fun forArrow(id: Int): arrow.core.Either<NotFound, Pair<String, List<String>>> = dashboard(id).toArrow()

fun forSurefold(id: Int): Either<NotFound, String> = name(id).toSurefold()
```

That lets a service move one function at a time, with each side calling the
other through the bridge until nothing on the Arrow side is left.
