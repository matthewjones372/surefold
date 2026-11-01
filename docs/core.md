# Core: concurrency, errors and resources

`surefold` is the module everything else builds on. It provides structured
concurrency on virtual threads, typed errors through Surefold's own `Fail`, resource
safety, schedules, timeouts and call policies, and the context that forks
inherit. Everything on this page is in the package
`io.github.matthewjones372.surefold`.

```kotlin
dependencies {
    // brings nothing but the Kotlin standard library
    implementation("io.github.matthewjones372:surefold:0.17.0")
}
```

The complete examples on this page, the fences that carry their own imports,
are compiled by `CoreGuideTest`.

1. [Forking](#forking)
2. [Failure and cancellation](#failure-and-cancellation)
3. [Traversing a list](#traversing-a-list)
4. [Collecting every error](#collecting-every-error)
5. [Resources](#resources)
6. [Schedules, retries and timeouts](#schedules-retries-and-timeouts)
7. [Policies](#policies)
8. [Values changed together](#values-changed-together)
9. [What a fork inherits](#what-a-fork-inherits)
10. [Running forks on Pekko](#running-forks-on-pekko)
11. [Cache retention](#cache-retention)
12. [Cache release](#cache-release)
13. [Requirements and limitations](#requirements-and-limitations)

## Forking

The combinators have the same names and shapes as `arrow-fx-coroutines`.
Inside an `attempt { }` (or any `Fail<E>`), a branch can `bind()` an `Either`
or `fail` with an error, and the first failure becomes the block's `Left`:

<!-- core-forking -->
```kotlin
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.attempt
import io.github.matthewjones372.surefold.fail.bind
import io.github.matthewjones372.surefold.parMap
import io.github.matthewjones372.surefold.parZip
import io.github.matthewjones372.surefold.raceN
import io.github.matthewjones372.surefold.scope

data class Err(val why: String)

data class User(val id: Int)

data class Order(val id: Int)

data class Dashboard(val user: User, val orders: List<Order>)

// Blocking stand-ins for a repository and a client.
fun findUser(id: Int): Either<Err, User> = Either.Right(User(id))

fun ordersFor(id: Int): Either<Err, List<Order>> = Either.Right(listOf(Order(1)))

fun quoteFrom(source: String): Long = source.length.toLong()

// Two branches at once; up to nine are supported.
fun dashboard(id: Int): Either<Err, Dashboard> = attempt {
    parZip({ findUser(id).bind() }, { ordersFor(id).bind() }) { user, orders -> Dashboard(user, orders) }
}

// One fork per element, answered in the input's order.
fun users(ids: List<Int>): Either<Err, List<User>> = attempt { parMap(ids) { findUser(it).bind() } }

// The first branch to answer wins, and the loser is interrupted. Left is the first branch, Right the second.
fun quote(): Either<Long, Long> = raceN({ quoteFrom("primary") }, { quoteFrom("fallback") })

// async and await, for forks the combinators do not shape.
fun report(id: Int): Either<Err, Dashboard> = attempt {
    scope {
        val user = async { findUser(id).bind() }
        val orders = async { ordersFor(id).bind() }
        Dashboard(user.await(), orders.await())
    }
}
```

- `parZip` takes two to nine branches. `raceN` takes two or three; with three
  it answers `Either<A, Either<B, C>>`.
- Outside a `Fail`, `parZip`, `parMap` and `raceN` take plain `() -> A`
  branches and return the combined value.
- `parMap` starts one fork per element and has no concurrency limit. To cap
  it, pass a [`Limit`](stream.md#a-limit-nobody-has-to-pick):
  `parMap(ids, limit = ordersDb) { … }` keeps every call inside it, and never
  has more forks than its `max`. One limit shared with a stream's `mapPar` and
  a policy bounds all three together.
- `scope { }` is the scope that `async` forks in. On its own nothing in it can
  raise, so it returns `A`. Inside an existing `Fail<E>`, such as `attempt { }`,
  it is that `Fail`'s scope: a failure in it, or in any fork, becomes the
  enclosing `Left`.

By default each fork is a new virtual thread. Every forking combinator also
has an overload that takes the executor to fork on as its first argument, for
example `parZip(on = pool, { … }, { … }) { … }` or `timeout(on = pool, 2.seconds) { … }`.
`scope(on = pool) { }` and `async(on = pool) { }` take it as a defaulted
parameter.

### Lazy forks

`async(start = Start.Lazy) { }` does not reach the executor until something
awaits it, so work that a later branch decides it does not need is never
started. A lazy fork that is never awaited never runs, so it cannot fail the
scope either. The choice is made per fork rather than per scope, because a
scope where nothing started until it was awaited would turn a fan-out into
sequential code.

## Failure and cancellation

A scope does not return until every fork it started has ended. That is the
property everything else on this page relies on: when `parZip`, `scope` or
`timeout` returns, normally or by raising or throwing, none of its work is
still running.

- **The first failure stops the rest.** When a branch fails or throws, its
  siblings are interrupted and joined, and the combinator answers with that
  failure. If several branches fail on their own, the one started first wins;
  an exception thrown only because a branch was interrupted is not reported.
- **Unawaited forks still count.** A `Deferred` that nobody awaits is joined
  when its `scope` closes, and if it failed, the failure becomes the block's
  `Left`. If it threw, the exception is rethrown.
- **Cancellation is interruption.** The JDK has no other cancellation, so a
  cancelled branch stops at its next interruptible blocking call: a sleep, a
  lock, a socket read, `Future.get`. A branch that is computing without
  blocking runs on until it reaches one.
- **`cancel()` stops one fork.** `deferred.cancel()` interrupts that fork and
  returns once it has ended. A cancelled fork's outcome counts as handled, so
  the scope does not answer with it even if it failed before the interrupt
  arrived. If its body swallowed the interrupt and returned a value, `await()`
  after `cancel()` returns that value.
- **The interrupt flag does not leak.** A fork clears the flag as its body
  exits, so a pooled executor's next task does not start interrupted.

<!-- core-cancel -->
```kotlin
import io.github.matthewjones372.surefold.scope

data class Credit(val hasCredit: Boolean)

fun buildReport(id: Int): String {
    Thread.sleep(500) // interruptible, so cancel() stops it here
    return "report for $id"
}

fun creditFor(id: Int): Credit = Credit(hasCredit = id % 2 == 0)

// Start the expensive work early, and stop it if the cheap check says it is not wanted.
fun page(id: Int) = scope {
    val report = async { buildReport(id) }
    if (creditFor(id).hasCredit) {
        report.await()
    } else {
        report.cancel()
        "no report"
    }
}
```

A `Fail` scope never crosses a thread on its own: a `fail` from a thread the
scope did not fork is refused. The combinators carry a branch's failure back
to the scope that started it, but work handed to some
other thread, such as a callback, should answer with an `Either` that the
handler binds.

## Traversing a list

`traverse` runs a function that can fail over every element, in order on the
calling thread, and answers with every value or with the first failure; no
element after it runs. `parTraverse` runs each element on a fork of its own,
and the first to fail interrupts the rest.

`Either.cond(condition, right = { value }, left = { error })` constructs one result from a Boolean;
only the selected callback runs. Use `ensure` inside `attempt` for dependent checks, and accumulation
for independent field validation. `cond` propagates callback exceptions. Available in the development
snapshot for spec 0268, not the published 0.17.0 release.

`Either.recover` supplies a fallback only on failure. Its class-token member leaves unmatched Left values
unchanged; matching handlers can return a value, bind an alternative or fail through the existing Fail scope.
The selective form keeps E and A fixed; declare A nullable at capture when null is a valid fallback. The total
extension can change E; supply its result type when a value-only handler gives the new error type no constraints.
Recovery does not capture exceptions thrown by its handler: capture foreign calls explicitly.
These overloads are in the spec 0270 development snapshot, not published 0.17.0.

<!-- core-recover -->
```kotlin
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.bind
import io.github.matthewjones372.surefold.fail.recover
import java.io.IOException

fun backup(): Either<Throwable, String?> = Either.catch { "backup" }

fun recoverResponse(result: Either<Throwable, String?>): Either<Throwable, String?> =
    result.recover(IOException::class) { backup().bind() }

fun optionalResponse(result: Either<Throwable, String?>): Either<Throwable, String?> =
    result.recover(IOException::class) { null }

fun defaultResponse(result: Either<String, Int>): Either<Nothing, Int> = result.recover { it.length }
```

<!-- core-traverse -->
```kotlin
import io.github.matthewjones372.surefold.Limit
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.bind
import io.github.matthewjones372.surefold.fail.traverse
import io.github.matthewjones372.surefold.parTraverse

data class User(val id: Int)

data class NotFound(val id: Int)

fun load(id: Int): Either<NotFound, User> =
    Either.cond(condition = id > 0, right = { User(id) }, left = { NotFound(id) })

// One at a time; stops at the first NotFound.
fun loadAll(ids: List<Int>): Either<NotFound, List<User>> = ids.traverse { id -> load(id).bind() }

// At most 64 at once, the default.
fun loadAllAtOnce(ids: List<Int>): Either<NotFound, List<User>> = ids.parTraverse { id -> load(id).bind() }

// One limit per dependency, shared by every caller, so traversals count against its budget.
val users = Limit.adaptive("users")

fun loadAllFromUsers(ids: List<Int>): Either<NotFound, List<User>> =
    ids.parTraverse(users) { id -> load(id).bind() }
```

- **Bounded by default.** With no `Limit`, `parTraverse` runs at most
  `PAR_TRAVERSE_DEFAULT` (64) elements at once, so a long list never parks a
  thread per element. A `Limit` you pass replaces it.
- **Inside a scope, `map` is the traversal.** In an `attempt { }` block,
  `ids.map { load(it).bind() }` does what `traverse` does; `traverse` is for
  code outside one.
- A thrown exception is not a failure. It leaves `traverse` unchanged, as it
  leaves `attempt`.

| ZIO | Surefold |
|---|---|
| `ZIO.foreach` | `traverse`, or `map` inside `attempt` |
| `ZIO.foreachDiscard` | `forEach` inside `attempt` |
| `ZIO.foreachPar` | `parTraverse` |
| `ZIO.foreachPar(…).withParallelism(n)` | `parTraverse(Limit.fixed(n))` |
| `ZIO.validate` | `mapOrAccumulate`, `parMapOrAccumulate` |

## Collecting every error

`zipOrAccumulate` and `mapOrAccumulate` run every branch, in order, on the
calling thread, and fail with all the errors instead of only the first, which
suits validation:

<!-- core-accumulate -->
```kotlin
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.NonEmptyList
import io.github.matthewjones372.surefold.fail.attempt
import io.github.matthewjones372.surefold.fail.bindAll
import io.github.matthewjones372.surefold.fail.ensure
import io.github.matthewjones372.surefold.mapOrAccumulate
import io.github.matthewjones372.surefold.zipOrAccumulate

data class Form(val name: String, val email: String)

data class Account(val name: String, val email: String)

data class Invalid(val field: String)

fun validate(form: Form): Either<NonEmptyList<Invalid>, Account> = attempt {
    zipOrAccumulate(
        { form.name.also { ensure(it.isNotBlank()) { Invalid("name") } } },
        { form.email.also { ensure('@' in it) { Invalid("email") } } },
    ) { name, email -> Account(name, email) }
}

fun validateAll(forms: List<Form>): Either<NonEmptyList<Invalid>, List<Account>> = attempt {
    mapOrAccumulate(forms) { form -> validate(form).bindAll() }
}
```

- Errors come back in branch order, or the input's order.
- `bindAll()` keeps every error of a nested `NonEmptyList`. It compiles only
  inside a branch, where something will keep them.
- Each function also has a form taking `combine: (E, E) -> E` as its first
  argument, for a scope with a single error type that knows how to merge two.
- A thrown exception is not accumulated: it ends the call where it happens.

When the number of checks is not known up front, `accumulate { }` keeps what
`ensureOrAccumulate` and `bindOrAccumulate` record and carries on, then fails
with all of it once the block is over:

<!-- core-accumulate-block -->
```kotlin
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.NonEmptyList
import io.github.matthewjones372.surefold.fail.accumulate
import io.github.matthewjones372.surefold.fail.attempt
import io.github.matthewjones372.surefold.fail.ensureOrAccumulate

data class Line(val sku: String, val qty: Int)

data class Invalid(val field: String)

fun check(lines: List<Line>): Either<NonEmptyList<Invalid>, List<Line>> = attempt {
    accumulate {
        ensureOrAccumulate(lines.isNotEmpty()) { Invalid("lines") }
        lines.forEach { line -> ensureOrAccumulate(line.qty > 0) { Invalid("qty:${line.sku}") } }
    }
    lines
}
```

`bindOrAccumulate()` answers `null` once it has recorded an error. A plain
`fail` inside the block keeps its error too, but ends the block.

`parZipOrAccumulate` and `parMapOrAccumulate` take the same arguments and run
each branch on a fork of its own. They are worth it when the branches wait on
I/O; for checks that only compute, the sequential forms avoid the forks.

<!-- core-accumulate-par -->
```kotlin
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.NonEmptyList
import io.github.matthewjones372.surefold.fail.attempt
import io.github.matthewjones372.surefold.fail.bind
import io.github.matthewjones372.surefold.parZipOrAccumulate

data class Invalid(val field: String)

fun lookUp(id: String): Either<Invalid, String> = Either.Right(id)

fun both(a: String, b: String): Either<NonEmptyList<Invalid>, String> = attempt {
    parZipOrAccumulate({ lookUp(a).bind() }, { lookUp(b).bind() }) { x, y -> x + y }
}
```

A thrown exception in a `par` form interrupts the other branches, as in
`parZip`, and the same instance is rethrown.

## Resources

`resourceScope { }` runs a block with resources acquired inside it and releases
them in reverse order when the block exits, whether by returning, failing,
throwing or being interrupted. `install` takes an acquire and a release, and the
release is told how the scope ended:

<!-- core-resources -->
```kotlin
import io.github.matthewjones372.surefold.ExitCase
import io.github.matthewjones372.surefold.Resource
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.attempt
import io.github.matthewjones372.surefold.resource
import io.github.matthewjones372.surefold.resourceScope
import io.github.matthewjones372.surefold.use

class Connection {
    fun rows(id: Int): List<String> = listOf("row-$id")

    fun commit() = Unit

    fun rollback() = Unit

    fun close() = Unit
}

data class NotFound(val id: Int)

val connection: Resource<Connection> = resource {
    install({ Connection() }) { held, exit ->
        if (exit is ExitCase.Failure) held.rollback() else held.commit()
        held.close()
    }
}

fun report(id: Int): Either<NotFound, String> = attempt {
    resourceScope {
        val db = connection.bind()                                  // released when this scope ends
        val out = install({ StringBuilder() }) { _, _ -> }
        val rows = db.rows(id).ifEmpty { fail(NotFound(id)) }       // a fail releases both, in reverse
        out.append(rows.joinToString()).toString()
    }
}

// A single resource for the length of one block.
fun rows(id: Int): List<String> = connection use { it.rows(id) }
```

`ExitCase` has three cases:

| Case | When |
|---|---|
| `ExitCase.Completed` | the block returned, or failed |
| `ExitCase.Failure(failure)` | the block threw |
| `ExitCase.Cancelled(interrupt)` | the block was interrupted; it carries the `InterruptedException` |

A fail releases with `Completed`: it is a declared answer, and the `attempt`
it leaves through returns a value. Every release runs even if an earlier one
throws. A release that throws when nothing else failed becomes the exception
the caller sees; one that throws after another failure is added to it as
suppressed.

In an application, the graph in [`surefold-app`](app.md) uses the same `install`
for each node, and releases nodes in reverse dependency order.

## Schedules, retries and timeouts

A `Schedule<Input, Output>` is a value that answers each input with a
decision: continue after a delay, or stop. `retry` feeds it what the action
threw, and `repeat` feeds it what the action returned. The delays are spent
on the calling thread through the inherited [clock](#what-a-fork-inherits), so
an interrupt ends the schedule where it is and a test can skip the waiting.

<!-- core-schedule -->
```kotlin
import io.github.matthewjones372.surefold.Schedule
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.attempt
import io.github.matthewjones372.surefold.fail.bind
import io.github.matthewjones372.surefold.retry
import io.github.matthewjones372.surefold.retrying
import io.github.matthewjones372.surefold.timeout
import io.github.matthewjones372.surefold.timeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class Unavailable(val id: Int)

fun fetchRow(id: Int): String = "row-$id"

fun findRow(id: Int): Either<Unavailable, String> = Either.Right("row-$id")

fun pending(): Long = 0

// Exponential backoff with jitter, at most five retries.
val backoff = Schedule.exponential<Throwable>(100.milliseconds).jittered() and Schedule.recurs(5)

fun fetch(id: Int): String = backoff.retry { fetchRow(id) }          // rethrows the last failure

// Bounded by elapsed time rather than by attempts.
val patiently = Schedule.spaced<Throwable>(20.milliseconds) zipLeft Schedule.upTo(5.seconds)

fun drained(): Unit = patiently.retry { check(pending() == 0L) }

// Retrying a declared error: the last failure is the Left.
fun row(id: Int): Either<Unavailable, String> = Schedule.recurs<Unavailable>(5).retrying { findRow(id).bind() }

fun quote(id: Int): Either<Unavailable, String> = attempt {
    timeout(2.seconds) { findRow(id).bind() }                       // throws TimeoutException
}

fun maybeQuote(id: Int): Either<Unavailable, String?> = attempt {
    timeoutOrNull(2.seconds) { findRow(id).bind() }                 // null instead
}
```

Builders: `recurs`, `spaced`, `upTo`, `exponential`, `linear`, `fibonacci`,
`forever`, `identity`, `doWhile`, `doUntil` and `collect`. Combinators: `and`,
`andThen`, `zipLeft` and `zipRight` (all infix), `or` (with explicit functions
to combine the outputs and the delays), `map`, `fold`, `delayed` and
`jittered`. Inside a `Fail`, `retry(schedule) { }` retries failures rather than
throws.

`timeout` runs its block on a fork and races it against a sleeper. A block that
does not finish in time is interrupted, and `timeout` throws
`java.util.concurrent.TimeoutException` once the block has ended. As with any
cancellation, a block that never reaches an interruptible call cannot be
stopped early. The sleeper waits on the inherited clock, so under
`fixedClock()`, whose sleeps return at once, a `timeout` gives up immediately;
use a `TestClock` to test one.

## Policies

A `policy` combines a deadline, retries, guards and a per-attempt timeout into
one value. It is checked when it is built and applied as a whole on every call:

<!-- core-policy -->
```kotlin
import io.github.matthewjones372.surefold.Bulkhead
import io.github.matthewjones372.surefold.CircuitBreaker
import io.github.matthewjones372.surefold.RateLimiter
import io.github.matthewjones372.surefold.Schedule
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.attempt
import io.github.matthewjones372.surefold.guarded
import io.github.matthewjones372.surefold.policy
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class Partner(private val call: (String) -> String) {
    private val breaker = CircuitBreaker(
        name = "partner-api",
        maxFailures = 5,
        resetAfter = Schedule.exponential<Unit>(1.seconds).jittered(0.8, 1.2) zipLeft Schedule.recurs(6),
    )
    private val limiter = RateLimiter(name = "partner-api", rate = 50, per = 1.seconds, burst = 10, maxWait = 200.milliseconds)
    private val bulkhead = Bulkhead(name = "partner-api", maxConcurrent = 20, maxWait = 50.milliseconds)

    private val partner = policy("partner-api") {
        deadline(2.seconds)
        retry(Schedule.exponential<Throwable>(100.milliseconds).jittered() zipLeft Schedule.recurs(3))
        guard(breaker)
        guard(limiter, cost = 1)
        guard(bulkhead)
        attemptTimeout(500.milliseconds)
    }

    fun quote(id: String): String = partner { call(id) }

    // A refusal by any guard, as a declared error rather than an exception.
    fun quoted(id: String): Either<String, String> = attempt {
        guarded(partner, ifRejected = { "${it.guard} refused: ${it.message}" }) { call(id) }
    }
}
```

Steps must appear in the order deadline, retry, breaker, limiter, bulkhead,
attemptTimeout. A step out of order, a repeated step, or an attempt timeout
longer than the deadline throws `IllegalArgumentException` when the policy is
built, not on the first call. Each step is optional.

- **`deadline(total)`** is one time budget for the whole call, retries
  included. Retrying stops before a delay the budget cannot cover, and each
  attempt is limited to the shorter of its own timeout and what is left.
- **`retry(schedule)`** retries what the call throws. A `fail` is the call's
  own answer: it passes through every step and is not retried.
- **A `CircuitBreaker`** opens after `maxFailures` failures in a row and then
  refuses every call with `Rejected.CircuitOpen` (carrying `retryAt`) without
  making it. Once the next delay of its `resetAfter` schedule has passed, one
  trial call goes through. If it succeeds the breaker closes and the schedule
  starts again; if it fails the breaker reopens for the schedule's next delay.
  A timeout inside the breaker counts as a failure; a fail never does.
- **A `RateLimiter`** is a token bucket refilled at `rate` every `per`, holding
  at most `burst`. A call without a token reserves the next one and waits for
  it, so callers are served in arrival order. A call that would wait longer
  than `maxWait` (or the deadline) is refused at once with
  `Rejected.RateLimited` and its `retryAfter`. `cost` weighs a call the quota
  counts as several.
- **A `Bulkhead`** lets at most `maxConcurrent` callers in at once, in arrival
  order. The rest wait up to `maxWait` (or the deadline) and are then refused
  with `Rejected.BulkheadFull`. Virtual threads make callers cheap, so a
  bulkhead is what keeps thousands of them off a pool of twenty connections.
  `guard(limit, maxWait)` takes a [`Limit`](stream.md#a-limit-nobody-has-to-pick)
  in its place, which can find its own level and is shared with every other
  caller of the dependency.
- **`attemptTimeout(each)`** limits each attempt.

Called as `partner { … }`, a refusal throws its `Rejected`, which is a
`RuntimeException`. `guarded(policy, ifRejected) { }` turns it into a fail.

Each guard records metrics through surefold's [metrics](observability.md#metrics):

| Metric | Kind | Tags |
|---|---|---|
| `surefold.policy.calls` | counter | `policy`, `outcome` (`success`, `failure`, `rejected`), `refused_by` (`deadline`, a guard's name, or `none`) |
| `surefold.breaker.state` | gauge | `name`; 0 closed, 1 half-open, 2 open |
| `surefold.breaker.calls` | counter | `name`, `outcome` (`success`, `failure`, `rejected`) |
| `surefold.bulkhead.in_use` | gauge | `name` |
| `surefold.bulkhead.calls` | counter | `name`, `outcome` (`admitted`, `rejected`) |
| `surefold.limit.current`, `surefold.limit.in_use` | gauge | `name` |
| `surefold.ratelimiter.calls` | counter | `name`, `outcome` (`immediate`, `waited`, `rejected`) |
| `surefold.ratelimiter.wait` | histogram, seconds | `name` |

## Quiet handoff decisions

`HandoffDecision<A : Any>` (unreleased) records one final ownership decision. Construct it before acquiring resources.
`tryComplete(value)` publishes the first exact nonnull reference; later attempts return false. `peek()` returns it,
or null while pending. A successful completion publishes prior writes to a reader that observes its value.
The decision's ordinary path preserves interruption and uses no callbacks, metrics, turns, waiting or wakeup.
Payloads are not copied or frozen; later mutation needs caller coordination. A visible decision can precede the
completing method's return: it is not a caller/thread join or joined resource release. Retryable-transaction use is
unsupported. Existing Promise/Shared instrumentation remains enabled; arbitrary agents or VM-fatal failures are not
covered by this ordinary-path contract.

## Values changed together

A `Shared<T>` is a value forks share, and `transact { }` changes several of them
as one step: every write lands when the step commits, or none does. There is no
lock to take and no lock order to get wrong (spec 0209).

Take a box office that sells seats in several sections, with many buyers
checking out at once. One order holds seats in the stalls and the circle, and
gets all of them or none. A short section makes it wait until someone releases
seats. No seat is sold twice, and a cancelled order never leaves seats
half-held:

<!-- core-transact -->
```kotlin
import io.github.matthewjones372.surefold.Shared
import io.github.matthewjones372.surefold.fail.Fail
import io.github.matthewjones372.surefold.parMap
import io.github.matthewjones372.surefold.timeoutOrNull
import io.github.matthewjones372.surefold.transact
import kotlin.time.Duration.Companion.seconds

data class SoldOut(val section: String)

data class Order(val id: String, val seats: Map<String, Int>)

class BoxOffice(sections: Map<String, Int>) {
    private val free: Map<String, Shared<Int>> = sections.mapValues { Shared(it.value) }
    private val held = Shared(emptyMap<String, Map<String, Int>>())
    private val closed = Shared(emptySet<String>())

    // Every section the order wants, or none. Waits while any section is short.
    fun hold(order: Order) = transact {
        order.seats.forEach { (section, n) ->
            val left = free.getValue(section).get()
            if (left < n) waitForChange()
            free.getValue(section).set(left - n)
        }
        held.update { it + (order.id to order.seats) }
    }

    // Gives the seats back, which wakes any order waiting on those sections.
    fun release(orderId: String) = transact {
        val seats = held.get()[orderId] ?: return@transact
        seats.forEach { (section, n) -> free.getValue(section).update { it + n } }
        held.update { it - orderId }
    }

    fun close(section: String) = transact { closed.update { it + section } }

    // A closed section is a declared failure, and the step writes nothing.
    fun Fail<SoldOut>.holdOpen(order: Order) = transact {
        order.seats.keys.firstOrNull { it in closed.get() }?.let { fail(SoldOut(it)) }
        hold(order) // joins this step
    }

    // For metrics and logs: deciding a write on it races every other step.
    fun seatsLeft(section: String): Int = free.getValue(section).peek()
}

fun sell(office: BoxOffice, orders: List<Order>) {
    // However the buyers interleave, no seat is sold twice.
    parMap(orders) { office.hold(it) }

    // Waits up to two seconds. On timeout the step is interrupted and nothing is held.
    timeoutOrNull(2.seconds) { office.hold(Order("o-42", mapOf("stalls" to 2, "circle" to 1))) }
}

// Holds from whichever box office can sell now, preferring the first.
fun holdAtEither(east: BoxOffice, west: BoxOffice, order: Order) =
    transact { firstOf({ east.hold(order) }, { west.hold(order) }) }
```

- **`get`, `set` and `update`** work only inside `transact { }`. `peek()` reads
  the latest committed value from anywhere.
- **A step can run more than once.** When another commit changes something it
  read first, it runs again from the start. So a body changes `Shared` values
  and nothing else: an HTTP call or a log line inside it happens once per run,
  not once per commit.
- **A body never sees a half-committed state**, even on a run that is about to
  start again.
- **`waitForChange()`** parks the virtual thread until a value the step read is
  committed again, then runs the step again. A step that has read nothing
  cannot wait. Parking answers an interrupt, so `cancel()`, a lost race and
  `timeout { }` end a waiting step with nothing written.
- **`firstOf(...)`** answers the first branch that does not wait, dropping the
  writes of any that did. When every branch waits, the step waits on what any
  of them read.
- **A throw or a `fail` writes nothing.** Inside `attempt { }`, a `fail` from a
  step is the block's `Left`, as anywhere else.
- **A `transact` inside a `transact`** joins the outer step.
- **One value needs no written-out step.** `members.update { it + joined }`,
  `members.modify { it - left to (left in it) }` and
  `ready.awaitUntil { it >= 3 }` are each a step of their own, named after the
  `Shared`. Inside a step they join it, so `update` reads the same either way.
- **The compiler checks the body.** With the Gradle plugin applied, I/O, a
  blocking wait, a fork, a log line, a metric, a `tell`, a write to a `var`
  from outside the step or a `peek()` inside a step is a compile error. See
  [what the plugin refuses inside a step](app.md#what-the-compiler-plugin-refuses-inside-a-step).

The commit takes no lock, so a thread interrupted halfway through one holds
nobody up. Every commit does go through one shared counter, though, and a long
step that reads many values can keep losing to short ones that write them. Keep
steps short, and do slow work before or after them.

### Effects once the step commits

A step usually has to tell someone what it did: a log line, a metric, a
message to an actor. Written in the body, that happens once per run. Queued
with `afterCommit { }`, it happens once the step has committed, and only then:

<!-- core-after-commit -->
```kotlin
import io.github.matthewjones372.surefold.Shared
import io.github.matthewjones372.surefold.logInfo
import io.github.matthewjones372.surefold.transact

class Seats(count: Int) {
    private val free = Shared(count)

    // Written once the seats are held, however many times the step ran to hold them.
    fun hold(orderId: String, n: Int) = transact {
        val left = free.get()
        if (left < n) waitForChange()
        free.set(left - n)
        afterCommit { logInfo("held $n for $orderId, ${left - n} left") }
    }
}
```

- **Once per commit.** A run that starts again, waits, throws or fails drops
  what it queued. A `transact` inside a step queues on the outer one, and a
  `firstOf` branch that waited drops its effects with its writes.
- **In order, before `transact` returns,** on the thread that committed, so
  the caller sees them done.
- **An effect that throws keeps the commit.** The rest still run, then
  `transact` throws the first, with the others suppressed onto it.
- **An effect may call `transact`,** which starts a step of its own.
- **Not durable.** A crash between the commit and its effects loses them; a
  message that must survive one belongs in the [journal](actors.md#remembering).
- **To an actor,** `tellAfterCommit(ref, message)` from `surefold-actor` is
  `afterCommit { ref.tell(message) }`.

### Why a step ran again

A step and a `Shared` can each take a name, `transact("hold") { }` and
`Shared(120, name = "free.stalls")`, so that a step that keeps running again
says which value it lost to. Each is recorded through
[metrics](observability.md#metrics):

| Metric | Kind | Tags |
|---|---|---|
| `surefold.transact.runs` | counter | `transact`, `outcome` (`committed`, `solo`, `failed`, `cancelled`) |
| `surefold.transact.retries` | counter | `transact`, `cause`: the name of the first value it had read that changed, `unnamed`, or `waited` |
| `surefold.transact.attempts` | histogram | `transact`; runs per commit |

A step or a value without a name is `unnamed`. A name becomes a tag, so keep
names to a fixed set: a `Shared` made per order is better left unnamed. A
step that commits on its first run records one run and one attempt.

With `surefold-otel` on the classpath, a step that ran more than once also adds
one `transact.retried` event to the span it ran in when it commits, carrying
`transact`, `attempts` and a `retries.<cause>` count for each value it lost to,
and `solo` when it [ran alone](#when-a-step-keeps-losing).

### When a step keeps losing

A step that reads many values loses to every short step that writes one of
them first. Under steady contention it could run again forever, so after
`soloAfter` lost runs, 10 unless the step says otherwise, its next run is
alone: no other step commits until it has, so it commits on that run. Other
steps that try to commit meanwhile wait for it, then carry on.

<!-- core-solo -->
```kotlin
import io.github.matthewjones372.surefold.Shared
import io.github.matthewjones372.surefold.transact

val sections = List(1_000) { Shared(120) }

// Runs alone after 3 lost runs, rather than racing every hold on every section.
fun seatsLeft(): Int = transact("report", soloAfter = 3) { sections.sumOf { it.get() } }
```

A run alone is counted with `outcome=solo`. While it runs, every other commit
waits, so keep a step that may run alone short, and never have it wait on
another step's commit: alone, that commit waits on it. `soloAfter =
Int.MAX_VALUE` never runs alone.

## What a fork inherits

A plain virtual thread inherits nothing from the thread that started it. A
`SurefoldLocal` is a value that does cross: it is read on the thread that forks and
bound again inside the fork, whichever executor the fork runs on.

<!-- core-local -->
```kotlin
import io.github.matthewjones372.surefold.parMap
import io.github.matthewjones372.surefold.surefoldLocal

val requestId = surefoldLocal { "none" }

fun tagged(rows: List<String>): List<String> =
    requestId.locally("abc-123") {
        parMap(rows) { row -> "${requestId.get()}: $row" }   // every branch reads abc-123
    }
```

Surefold's own context uses the same mechanism:

- **The clock.** `clock` is a `SurefoldLocal<Clock>`. `Schedule`, `timeout`, probes,
  timers and stream operators all wait on it, so binding `fixedClock()` makes
  a five-retry backoff finish immediately, and a `TestClock` moves only when a
  test calls `adjust`. See [Testing](testing.md#time).
- **Log annotations and metric tags.** `logAnnotated` and `metricTagged` bind
  values that every log line and measurement inside the block carries,
  including on forks. See [Observability](observability.md).
- **Trace context.** With `surefold-otel` on the classpath, OpenTelemetry's
  `Context` is kept in a `SurefoldLocal`.

The same context also travels with an actor message, local or remote, and is
stored with journal events, so a correlation id or trace follows a request
through an actor system.

## Running forks on Pekko

A Pekko application already has one place that names, sizes and instruments
its threads: the dispatcher configuration. `surefold-pekko` makes a dispatcher
configured there the executor Surefold forks on, and lets a fork wait for Pekko's
`CompletionStage`s and Scala `Future`s.

```kotlin
dependencies {
    // brings surefold and pekko-actor
    implementation("io.github.matthewjones372:surefold-pekko:0.17.0")
}
```

The dispatcher is configured beside Pekko's own, and named by the part after
`pekko.actor.`:

```hocon
pekko.actor.surefold {
  executor = "virtual-thread-executor"
}
```

<!-- core-pekko -->
```kotlin
import io.github.matthewjones372.surefold.await
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.attempt
import io.github.matthewjones372.surefold.fail.bind
import io.github.matthewjones372.surefold.parZip
import io.github.matthewjones372.surefold.pekko.surefoldDispatcher
import org.apache.pekko.actor.ActorSystem
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

data class Missing(val id: Int)

fun price(id: Int): CompletionStage<Long> = CompletableFuture.completedFuture(100L * id)

fun stock(id: Int): Either<Missing, Int> = Either.Right(3)

fun offer(system: ActorSystem, id: Int): Either<Missing, Pair<Long, Int>> = attempt {
    val surefold = system.surefoldDispatcher()   // pekko.actor.surefold; surefoldDispatcher("other") for another
    parZip(on = surefold, { price(id).await() }, { stock(id).bind() }) { p, s -> p to s }
}
```

- `surefoldDispatcher` accepts a dispatcher whose executor is
  `virtual-thread-executor`, or one with `type = PinnedDispatcher`, whose thread
  is not shared. It rejects any other executor, including Pekko's default
  fork-join pool, with an `IllegalArgumentException` naming the setting: a
  Surefold fork blocks, and blocking the pool Pekko sizes for its actors starves
  them. Both the classic and the typed `ActorSystem` can call it.
- `await()` parks the calling fork until the stage completes. A failed stage
  rethrows the original cause, not the `CompletionException` around it. An
  interrupt cancels the stage with `cancel(true)` and rethrows the
  `InterruptedException`, so a value that arrives later is dropped.
- `await()` is in `surefold` and takes any `CompletionStage` or `Future`, Pekko's
  or not; Pekko's Scala futures have one of their own in `surefold-pekko`.
- The other way round, `surefoldFuture { }` hands surefold to a caller that wants a
  future: the block runs on a virtual thread of its own, with the caller's
  bindings as a fork has them, and the future's `cancel` interrupts it, so its
  forks stop. `CompletableFuture.supplyAsync` would ignore the cancel. A
  coroutine awaiting it with kotlinx's own `await()` cancels it when the
  coroutine is cancelled.
- `await()` does not change what a stage holds. A stream run into `Sink.seq`
  still collects every element before it completes.

## Futures and coroutines

A fork waits for a future with `await()`, and hands surefold to a caller that
wants one with `surefoldFuture { }`. Both are in `surefold`.

### Wait for a future

<!-- core-await -->
```kotlin
import io.github.matthewjones372.surefold.await
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class Rates(private val http: HttpClient) {
    fun rate(currency: String): String {
        val request = HttpRequest.newBuilder(URI.create("https://rates.example/$currency")).build()
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await().body()
    }
}
```

`await()` takes any `CompletionStage` or `Future`, such as the JDK's
`HttpClient`, an AWS SDK v2 async client or Kafka's `send`, and parks the
virtual thread until it completes. A failure is thrown as the cause itself,
not the `ExecutionException` around it, so catch the exception you declared.
A fork cancelled while it waits cancels the future too.

### Hand back a future

<!-- core-surefold-future -->
```kotlin
import io.github.matthewjones372.surefold.parMap
import io.github.matthewjones372.surefold.surefoldFuture
import java.util.concurrent.CompletableFuture

class Profiles(private val find: (Long) -> String?) {
    fun profilesAsync(ids: List<Long>): CompletableFuture<List<String?>> =
        surefoldFuture { parMap(ids) { id -> find(id) } }
}
```

For a caller that wants a future: Spring's `@Async`, Vert.x, a Java API. The
block runs on a virtual thread of its own and reads what the caller had bound,
as a fork does. The future's `cancel` interrupts it, so `parMap`, `parZip` and
`raceN` inside stop their branches. `supplyAsync` would ignore the cancel.

A single fork's `await()` ignores interrupts on purpose, so a block that waits
on one `async` runs on until that fork ends. Reach for the combinators.

### Call a coroutine library

Some libraries have only a `suspend` API: Ktor's client, the AWS SDK for
Kotlin. From surefold code, `runBlocking` is the right call, and nothing more is
needed:

```kotlin
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
```

<!-- core-run-blocking -->
```kotlin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

// Stands in for a library whose API is suspend only.
class ChipRegistry {
    suspend fun lookup(id: String): String {
        delay(10)
        return "chip-$id"
    }
}

class Chips(private val registry: ChipRegistry) {
    fun chip(id: String): String = runBlocking { registry.lookup(id) }
}
```

`runBlocking` parks one virtual thread, which is cheap. The block resumes on
that thread, so code in it keeps the log annotations and span the fork had. A
surefold cancel interrupts the thread, which cancels the coroutine. Log lines the
library writes on threads of its own go without surefold's annotations.

Don't `fail` inside the block. A coroutine can resume on a thread of its own,
and a `fail` from a thread the scope did not fork is refused. Get the value
out first, then fail.

### Be called from coroutines

<!-- core-from-coroutines -->
```kotlin
import io.github.matthewjones372.surefold.parZip
import io.github.matthewjones372.surefold.surefoldFuture
import kotlinx.coroutines.future.await

class Dashboard(private val users: (Long) -> String, private val orders: (Long) -> List<String>) {
    suspend fun load(id: Long): Pair<String, List<String>> =
        surefoldFuture { parZip({ users(id) }, { orders(id) }) { user, bought -> user to bought } }.await()
}
```

A Ktor route or any other `suspend` caller hands surefold its work with
`surefoldFuture`, and waits with kotlinx's `await`, which suspends rather than
blocks. Calling surefold straight from a coroutine would block a
`Dispatchers.Default` thread, of which there is one per core. When the
coroutine is cancelled, by a client that hung up for instance, kotlinx cancels
the future and surefold stops the branches.

Import kotlinx's `await` and not surefold's in a file like this. With both
imported, surefold's is the closer match for a `CompletableFuture` and wins, and it
blocks.

## Cache retention

`Cache` in `surefold-cache` fixes its lookup at construction and shares one lookup
per key through `SingleFlight`. Zero and infinite retention are available from
0.17.0. In 0.16.0, zero results can occupy retained capacity, and infinite TTL
can overflow.

```kotlin
dependencies {
    implementation("io.github.matthewjones372:surefold-cache:0.17.0") // includes surefold
}
```

### Share without retaining

`Duration.ZERO` delivers either a `Right` or a `Left` to overlapping waiters,
then forgets it. A later call rechecks layers and the source. Cancelling one
waiter leaves the shared lookup running for the others. Zero results consume
no retained capacity, write no layers and start no refresh-ahead schedule; a
zero result also stops an existing schedule without cancelling an active lookup
needed by its waiters.

<!-- core-cache-zero -->
```kotlin
import io.github.matthewjones372.surefold.cache.Cache
import io.github.matthewjones372.surefold.fail.right
import kotlin.time.Duration

fun noRetention() {
    Cache<String, Nothing, String>(
        ttl = { Duration.ZERO },
        onEvict = { _, _ -> error("a never-retained value has no eviction callback") },
    ) { key -> key.uppercase().right() }.use { cache ->
        check(cache.get("one") == "ONE".right())
        check(cache.get("one") == "ONE".right())
        check(cache.stats().loads == 2L)
        check(cache.stats().size == 0)
        check(cache.stats().evictions == 0L)
    }
}
```

A young layer hit whose result has zero TTL is delivered without admission to
memory or nearer layers. Its shared record stays untouched, so the next call
may read it again until its original expiry.

### Retain by capacity alone

`Duration.INFINITE` keeps an admitted fresh `Right` or `Left` in memory without
a time deadline. It performs no far-future `Instant` arithmetic. Capacity still
applies: TinyLFU can reject a new key or evict a retained one; invalidation and
replacement still remove retained values. Infinite retention does not guarantee
admission or reserve a slot for every key.

<!-- core-cache-infinite -->
```kotlin
import io.github.matthewjones372.surefold.Shared
import io.github.matthewjones372.surefold.TestClock
import io.github.matthewjones372.surefold.cache.Cache
import io.github.matthewjones372.surefold.cache.Invalidation
import io.github.matthewjones372.surefold.clock
import io.github.matthewjones372.surefold.fail.right
import java.time.Instant
import kotlin.time.Duration

fun capacityOnly() {
    val moving = TestClock()
    val removed = Shared(emptyList<String>())
    val cache = clock.locally(moving) {
        Cache<String, Nothing, String>(
            ttl = { Duration.INFINITE },
            capacity = 100,
            onEvict = { _, value -> removed.update { it + value } },
        ) { key -> key.right() }
    }
    cache.use {
        check(cache.get("one") == "one".right())
        moving.setTime(Instant.MAX)
        check(cache.get("one") == "one".right())
        check(cache.stats().loads == 1L)
        cache.invalidate(Invalidation.Key("one"))
        check(cache.stats().size == 0)
        check(removed.peek() == listOf("one"))
    }
}
```

Fresh infinite results never write layers: `Stored` and Redis keep their finite
expiry format. A finite layer hit under an infinite local policy still keeps its
original expiry in memory and nearer layers; local policy cannot extend it.
Failures stay in memory only, under every retention policy.

### Result-specific policy and fallback

Finite TTL keeps its exact boundary: an answer is young strictly before its
expiry. The cache captures the bound clock at construction. A finite `Left` TTL
still controls retry traffic; configured retries remain shared within one lookup.
A large finite TTL is not a substitute for infinite retention and can overflow.

This policy keeps good answers briefly and shares failures without retaining
them. At expiry a failed lookup may still answer with the last good value while
it is younger than `stale`; that fallback does not retain the new zero result.

<!-- core-cache-finite -->
```kotlin
import io.github.matthewjones372.surefold.Shared
import io.github.matthewjones372.surefold.TestClock
import io.github.matthewjones372.surefold.cache.Cache
import io.github.matthewjones372.surefold.clock
import io.github.matthewjones372.surefold.fail.Either
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun zeroFailureFallback() {
    val moving = TestClock()
    val source = Shared<Either<String, String>>(Either.Right("last good"))
    val cache = clock.locally(moving) {
        Cache<String, String, String>(
            ttl = { if (it is Either.Right) 1.seconds else Duration.ZERO },
            stale = 10.seconds,
        ) { source.peek() }
    }
    cache.use {
        check(cache.get("one") == Either.Right("last good"))
        source.update { Either.Left("unavailable") }
        moving.adjust(1.seconds)
        check(cache.get("one") == Either.Right("last good"))
        check(cache.stats().stale == 1L)
        check(cache.stats().size == 0)
        check(cache.get("one") == Either.Left("unavailable"))
    }
}
```

An expired prior entry is removed after answering this zero-result fallback.
A failed explicit or ahead refresh of a still-young value preserves that value,
including for a zero failure TTL, but zero stops further refresh-ahead. An infinite
failure TTL cannot extend the finite stale deadline. Infinite retention can use
a finite refresh-ahead schedule; an infinite schedule delay starts no sleeper.

`stats().size` counts retained entries. Each `get` is a hit or miss; concurrent
misses can share one load, and layer hits need no source load. Failures and stale
answers are still counted. Never-retained zero outcomes cause neither an
eviction nor `onEvict`: their recipients own their lifetime. Existing retained
value replacement and invalidation callbacks remain once-only under their
existing rules; replacement is not an eviction count.

Invalidation advances a global generation: older phases not yet admitted are suppressed, even for
another key.
Already-admitted unrelated phases finish independently; a key removal waits for earlier conflicting
phases, and `All` is a common barrier.
Each publication, layer readmission, layer write and schedule effect checks generation and closing
when admitted.
Invalidation attempts farthest-layer removal first, then memory and its eviction notifications
before returning; an unavailable layer may retain its record.
Layers must not call, or synchronously await calls to, their owning Cache's `get`, `refresh` or
`invalidate`; direct calls and work inheriting an active layer's Surefold ownership context fail
before admission or mutation. Foreign-executor cycles cannot be detected; ordinary layer-failure
handling still applies.
Other caches and `stats` remain callable; completing eviction, TTL and schedule callbacks retain
supported acyclic reentry, including a fresh `get`, without support for already-deadlocking cycles.

## Cache release

The awaited methods and same-cache layer reentry restriction are available from 0.17.0.

Register a dependency's release first, then its cache's awaited release.
`resourceScope` releases in reverse order, so cache work and cleanup finish while
the client is still available. An existing Resource release can call
`closeAndJoin`; no new resource type is needed. A cache-owned lookup or callback
must not be the owner releasing the surrounding scope.

<!-- core-cache-release -->
```kotlin
import io.github.matthewjones372.surefold.cache.Cache
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.right
import io.github.matthewjones372.surefold.resourceScope
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration.Companion.minutes

fun cachedBody(uri: URI): Either<Nothing, String> = resourceScope {
    val client = HttpClient.newHttpClient()
    onRelease { client.close() }
    val cache = Cache<URI, Nothing, String>(ttl = { 1.minutes }) { key ->
        client.send(
            HttpRequest.newBuilder(key).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body().right()
    }
    onRelease { cache.closeAndJoin() }
    cache.get(uri)
}
```

`Cache.closeAndJoin()` closes admission, interrupts accepted lookups, explicit
refreshes and sleepers, then joins actual workers and owned layer, TTL, schedule
and `onEvict` work before clearing memory. Accepted late work may answer existing
waiters, but cannot admit new retained, layer or schedule effects after closing.
New `get`, `refresh` and `invalidate` calls fail with `IllegalStateException`;
`stats` remains readable. Concurrent and repeated releases share completion,
including after an earlier ordinary `close()`.

Interruption of the owner does not return early: joining and cleanup finish,
then its interrupt flag is restored. Cancellation-resistant code or cleanup can
delay release indefinitely. Owned lookup, cleanup and callback self-close is
rejected before state changes; an external owner performs the release.

Memory is cleared after joining. `onEvict` runs once per removed `(key, value)`,
including distinct keys holding the same object; shared layers remain intact.
Every cleanup is attempted after a defect, aggregating new cleanup defects with
suppressed failures. Worker outcomes already delivered to waiters are not
reported again. A never-retained result does not become an eviction, and finite
layer expiry formats and the retention choices above remain unchanged.

Existing `close()`, `.use {}` and `AutoCloseable` remain interrupt-only with their
existing source and binary surface: return does not prove workers or their
`finally` blocks have ended. Awaited release is an explicit adoption.
`SingleFlight.closeAndJoin()` gives bare flights the terminal joined boundary;
`cancelAll()` stays reusable and interrupt-only. Cancelling one waiter still
leaves shared work and other waiters alone. The intentional behavior restriction
is same-cache layer reentry; existing non-reentrant callers keep their surface.

## Requirements and limitations

- **JDK 25 or newer.** Before JDK 24, a virtual thread blocked inside a
  `synchronized` block pinned its carrier thread, and a service could run out
  of carriers when a driver or serializer synchronized internally. JEP 491
  removed that in JDK 24, and 25 is the first long-term-support release with
  it. The Gradle and compiler plugins are built for JDK 21, because they run
  inside the build tools.
- **No `suspend`.** No published module uses coroutines or depends on
  `kotlinx-coroutines`. Calling Surefold from coroutine code works as any blocking
  call does, on a dispatcher that tolerates blocking, or through `surefoldFuture`
  (see [Futures and coroutines](#futures-and-coroutines)).
- **Cancellation is cooperative.** It relies on interruption, so code that
  swallows `InterruptedException` or never blocks cannot be stopped early.
- **`surefold-structured`** is an unpublished variant of `parZip` (two and three
  branches), `parMap`, `raceN` (two branches) and `timeout` built on the JDK's
  `StructuredTaskScope`, so that forks appear under their caller in a thread
  dump and inherit `ScopedValue` bindings. It compiles against JDK 27, where
  that API is still a preview, and will not be published until the API is
  final.
