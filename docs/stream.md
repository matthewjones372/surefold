# Streams

`surefold-stream` provides `Stream<E, A>`, a pipeline whose declared failure is
part of its type. Elements can never be null, and running a stream answers an
`Exit` that is one of three cases:

- `Done(value)`: the run finished;
- `Failed(error)`: the pipeline ended with an `E` it declared;
- `Died(cause)`: something nobody declared was thrown, such as a bug or a
  broken dependency.

A stream is a description. Building one runs nothing and names no backend.
The backend is a value passed in where the run starts, so the same pipeline
can run on a virtual thread in production, on Pekko Streams beside existing
Pekko code, and on a test clock in a unit test.

Everything on this page is in `dev.surefold.stream`,
whichever module it ships in.

1. [Modules and backends](#modules-and-backends)
2. [One description, several backends](#one-description-several-backends)
3. [Backends](#backends)
4. [Operators](#operators)
5. [A body that must block](#a-body-that-must-block)
6. [Missing is a failure, not an empty stream](#missing-is-a-failure-not-an-empty-stream)
7. [How a failure travels](#how-a-failure-travels)
8. [One publisher, many readers](#one-publisher-many-readers)
9. [Optimisation, rendering and profiling](#optimisation-rendering-and-profiling)
10. [Time a test owns](#time-a-test-owns)
11. [On Pekko Streams](#on-pekko-streams)
12. [Dependencies](#dependencies)

## Modules and backends

| Module | Provides | Backend value |
|---|---|---|
| `surefold-stream` | `Stream`, `Pipe`, `Run`, `Exit`, every backend-neutral operator, `Hub` | none |
| `surefold-stream-forks` | a run as a pull loop on a virtual thread | `Forks()` |
| `surefold-stream-pekko` | a run as Pekko Streams stages, and the operators that take Pekko's types | `PekkoStreams(system)` |
| `surefold-stream-actors` | a run as `surefold-actor` actors | `Actors(scope)` |
| `surefold-stream-test` | every stage on the calling thread, on a `TestClock` | `TestStreams(clock)` |

Each backend module brings `surefold-stream` with it:

```kotlin
dependencies {
    // a pull loop on a virtual thread: brings surefold-stream and nothing else
    implementation("dev.surefold:surefold-stream-forks:0.18.0")
    // or Pekko Streams: brings surefold-stream, surefold-pekko and pekko-stream
    // implementation("dev.surefold:surefold-stream-pekko:0.18.0")
    // or actors of a surefold-actor scope
    // implementation("dev.surefold:surefold-stream-actors:0.18.0")

    // time a test owns
    testImplementation("dev.surefold:surefold-stream-test:0.18.0")
}
```

`Forks()` is the usual choice. Use `PekkoStreams` where a service already runs
Pekko Streams or needs Pekko's own sources and sinks.

## One description, several backends

A pipeline that fails with `Unpriced` when an order has no price, run on three
backends. `ReadmeExampleSuite` compiles this against all three:

<!-- backend-example -->
```kotlin
import dev.surefold.TestClock
import dev.surefold.stream.Exit
import dev.surefold.stream.Forks
import dev.surefold.stream.PekkoStreams
import dev.surefold.stream.Run
import dev.surefold.stream.Stream
import dev.surefold.stream.TestStreams
import dev.surefold.stream.filter
import dev.surefold.stream.from
import dev.surefold.stream.mapOrFail
import dev.surefold.stream.run
import dev.surefold.stream.runFold
import org.apache.pekko.actor.ActorSystem
import java.util.concurrent.CompletionStage

data class Order(val id: Int, val pence: Long?)

data class Unpriced(val id: Int)

val orders = listOf(Order(1, 1200), Order(2, 800), Order(3, 4500))

// What happens to an order, and the failure it can end with. No backend is named here.
val takings: Run<Unpriced, Long> =
    Stream.from(orders)
        .mapOrFail { order -> order.pence ?: fail(Unpriced(order.id)) }
        .filter { pence -> pence >= 1000 }
        .runFold(0L) { total, pence -> total + pence }

val system: ActorSystem = ActorSystem.create("shop")

val onPekko: CompletionStage<Exit<Unpriced, Long>> = takings.run(PekkoStreams(system))
val onForks: CompletionStage<Exit<Unpriced, Long>> = takings.run(Forks())
val inATest: CompletionStage<Exit<Unpriced, Long>> = takings.run(TestStreams(TestClock()))
```

Each run answers the same `Exit`. `run` returns a `CompletionStage` of it;
`start` returns a `Running` handle whose `exit` is the same stage, whose
`stop()` ends the run now as `Done` with what the sink has so far, and whose
`close()` stops it and waits. `Running::close` therefore works as the release
of an [application node](app.md).

## Backends

All four backends pass the same suite of pipelines in `surefold-stream-parity`,
which checks that each gives the same answers. They differ in how a run uses
threads and in what they accept.

- **`Forks()`** pulls the pipeline on one virtual thread. Operators that need
  concurrency, `mapPar`, `mapAsync`, `buffer`, `merge`, `flatMapMerge`,
  `conflateWithSeed`, start further forks on the executor given as `on`
  (virtual threads by default). Time-based operators (`tick`,
  `groupedWithin`, `restartOnDefect`) wait on Surefold's [clock](core.md#what-a-fork-inherits).
- **`PekkoStreams(system)`** compiles each operator to the Pekko stage it
  names, so Pekko's fusion, backpressure and materialisation apply. It is the
  only backend that runs the Pekko-specific operators.
- **`Actors(scope)`** runs each run as an actor that pulls a batch per step,
  with `mapPar`, `buffer` and the fan-in operators as child actors. It was
  measured faster than Forks for `mapPar`, starting runs, `buffer` and `merge`,
  and about a quarter slower for a plain element-at-a-time chain, but those
  numbers predate spec 0172, which rebuilt Forks' `buffer`, `mapPar` and
  `mapAsync`; re-run the [benchmarks](../bench/surefold-stream-benchmarks/README.md)
  before choosing between them on speed.
- **`TestStreams(clock)`** runs one stage at a time on the calling thread, in
  the same order every time, with every wait on the test's `TestClock`. See
  [Time a test owns](#time-a-test-owns).

**Refusals.** Forks, Actors and TestStreams cannot run a Pekko value: a stream
built from a Pekko `Source`, `Flow` or `Sink`, or an operator such as
`alsoTo` that takes one. Starting such a run on them answers `Died` before
anything is pulled, with an error naming the operator and the line that built
it. A Pekko value inside a `catchAll` or `orElse` recovery, or inside a
`flatMap` inner stream, is not checked in advance: it fails when the run
reaches it. No backend runs a value that belongs to another.

## Operators

Every operator below works on every backend except those marked *(Pekko)*,
which are in `surefold-stream-pekko` and run only on `PekkoStreams`. Every
operator also exists on `Pipe`, a reusable middle section of a pipeline.

| Operator | Answers |
|---|---|
| **Building** | |
| `Stream.from(elements: Iterable<A>): Stream<Nothing, A>` | a stream of what is already in hand |
| `Stream.from(source: Source<A, *>): Stream<Nothing, A>` | the way in from Pekko, whatever the source materialises: the value is dropped, since a stream has none to give *(Pekko)* |
| `Stream.fail(error: E): Stream<E, Nothing>` | a stream that ends with the failure it names |
| `Stream.empty(): Stream<Nothing, Nothing>` | no elements and no failure |
| `Stream.single(element: A): Stream<Nothing, A>` | the one element named; `A : Any`, so a nullable does not compile |
| `Stream.of(vararg elements: A): Stream<Nothing, A>` | the elements named, in order; with none of them, `empty()` |
| `Stream.tick(every: Duration, element: A, after: Duration = every): Stream<Nothing, A>` | `element` every `every`, the first one `after` the run starts; `kotlin.time.Duration`, as surefold's own `timeout` takes |
| `Stream.fromStage(stage: CompletionStage<A>): Stream<Nothing, A>` | the stage's value as one element; a `null` completion is `Died(NullPointerException)`, never `Done` with nothing |
| `Stream.fromStage(stage: CompletionStage<A>, ifNull: E): Stream<E, A>` | the same, with the absence named: a `null` completion is `Failed(ifNull)` |
| `Stream.blocking(open, next, wake, close): Stream<Nothing, A>` | a resource opened per run and read by blocking, on any backend: `next` answering `null` ends it, `stop()` calls `wake` to reach a `next` that is blocked, and `close` runs once however the run ended. Pekko reads it on its blocking-IO dispatcher |
| `Stream.blocking(open, next, ready, wake, close)` | the same, with `ready` saying the next `next` will not block. Pekko reads those along with the read before them and hands them to the stream together; Forks never asks |
| `Stream.read(open: () -> InputStream, chunkSize: Int = 65536): Stream<Nothing, ByteChunk>` | an `InputStream` as `ByteChunk`s of at most `chunkSize` bytes; see [Bytes in and out](#bytes-in-and-out) |
| `Hub<A>(capacity).subscribe(): Stream<Nothing, A>` | everything a `Hub` is sent while a run of it lasts, on any backend; the first run ever also gets what was published before it. See [one publisher, many readers](#one-publisher-many-readers) |
| `Stream<Nothing, A>.publishTo(hub: Hub<A>): Stream<HubRefused, A>` | each element published to the hub as it passes; a refusal, `Full` or `Closed`, ends the stream with it |
| `Stream.hooked(source: (RunHooks) -> Source<A, *>): Stream<Nothing, A>` | opt-in (`@SourceSeam`), for a module adding a source of its own: each run hands the source hooks, so it can drain on `stop()` and clean up once the run has ended. `surefold-kafka-pekko`'s `Kafka.subscribe` is built on it *(Pekko)* |
| `Stream<E, A>.orFailIfEmpty(error: E2): Stream<E2, A>` | a stream that emitted nothing fails with `error`, for `E : E2`; one that emitted is untouched |
| **Element by element** | |
| `Stream<E, A>.map(f: (A) -> B): Stream<E, B>` | `B` is bound to `Any`, so a nullable body does not compile |
| `Stream<E, A>.mapOrFail(f: Failing<E>.(A) -> B): Stream<E, B>` | as `map`, with the body in a `Fail<E>`: `fail(e)`, `bind` and `ensure` |
| `Stream<E, A>.filter(predicate: (A) -> Boolean): Stream<E, A>` | the elements that match, the only place one is dropped on purpose |
| `Stream<E, A>.mapAsync(parallelism: Int, f: (A) -> CompletionStage<B>): Stream<E, B>` | a body that answers a stage: up to `parallelism` at once, in the input's order; a `null` completion dies |
| `Stream<E, A>.mapPar(parallelism: Int, f: Fail<E>.(A) -> B): Stream<E, B>` | a body that must block: one virtual thread per element in flight, in a `Fail<E>`; `B : Any`, so no completion drops an element |
| `Stream<E, A>.mapPar(parallelism: Int, on: Executor, f: Fail<E>.(A) -> B): Stream<E, B>` | the same, with every body run on the executor it names |
| `Stream<E, A>.mapParKeyed(parallelism: Int, key: (A) -> Any, f: Fail<E>.(A) -> B): Stream<E, B>` | `mapPar` that keeps order only among elements with the same key: a slow body holds back its own key's results, not the others'. Forks and Pekko let keys pass each other; Actors and TestStreams keep full order, which keeps every key's too |
| `Stream<E, A>.mapParUnordered(parallelism: Int, f: Fail<E>.(A) -> B): Stream<E, B>` | `mapPar` that keeps no order: each result passes on as soon as its body is done, so a slow body holds back only itself. `on` as above |
| `Stream<E, A>.mapAsyncUnordered(parallelism: Int, f: (A) -> CompletionStage<B>): Stream<E, B>` | `mapAsync` that keeps no order, Pekko's name: each result passes on as its stage completes |
| `Stream<E, A>.mapPar(limit: Limit, f: Fail<E>.(A) -> B): Stream<E, B>` | `mapPar` under a shared [`Limit`](#a-limit-nobody-has-to-pick): as many bodies at once as the limit allows now, never more than its `max`. `mapParUnordered(limit)` likewise |
| `Stream<E, A>.mapParOrFail(parallelism: Int, f: Fail<F>.(A) -> B): Stream<F, B>` | `mapPar` with the failure read out of the body, on a stream that has not named one; `mapOrFail`'s suffix, and `on` as above |
| `Stream<E, A>.mapConcat(f: (A) -> Iterable<B>): Stream<E, B>` | each element's own elements, in its order; one that answers with none emits none |
| `Stream<E, A>.conflateWithSeed(seed: (A) -> S, aggregate: (S, A) -> S): Stream<E, S>` | a backlog collapsed while downstream is busy: `seed` starts the aggregate and `aggregate` folds each later element in |
| `Stream<E, A>.filterNot(predicate: (A) -> Boolean): Stream<E, A>` | `filter`'s mirror, Pekko's own name |
| `Stream<E, A>.flatMapConcat(f: (A) -> Stream<E2, B>): Stream<E, B>` | each element expanded into a stream of its own, one after another, for `E2 : E`; the inner stream may fail |
| `Stream<E, A>.flatMapMerge(breadth: Int, f: (A) -> Stream<E2, B>): Stream<E, B>` | the same with up to `breadth` inner streams running, their elements interleaved |
| `Stream<E, Stream<E2, B>>.flatten(): Stream<E, B>` | `flatMapConcat { it }`, under the name a stream built by `map` into a fetch is looked up by |
| `Stream<E, A>.scan(zero: S, f: (S, A) -> S): Stream<E, S>` | `zero` first, then what `f` carried after each element |
| `Stream<E, A>.statefulMap(create: () -> S, f: (S, A) -> Pair<S, B>, onComplete: (S) -> B?): Stream<E, B>` | state carried across elements as a Kotlin `Pair`; `onComplete` is the one last element the state may still owe |
| **Two streams as one** | |
| `Stream<E, A>.prepend(first: Stream<E, A>): Stream<E, A>` | `first`'s elements before this stream's, Pekko's name and Pekko's order |
| `Stream<E, A>.concat(next: Stream<E, A>): Stream<E, A>` | `next`'s elements after this stream's; both operands materialise at once, so a failure in either ends the run |
| `Stream<E, A>.take(n: Long)` / `.drop(n: Long)` | the first `n`, or everything after them |
| `Stream<E, A>.takeWhile(predicate)` / `.dropWhile(predicate)` | up to where the predicate first answers false, or from it, that element included |
| `Stream<E, A>.grouped(n: Int): Stream<E, List<A>>` | batches of `n`, the last one short; a read-only `List`, not the `java.util.List` Pekko hands back |
| `Stream<E, A>.sliding(n: Int, step: Int = 1): Stream<E, List<A>>` | a window of `n`, moved on by `step` |
| `Stream<E, A>.groupedWithin(n: Int, within: Duration): Stream<E, List<A>>` | batches of at most `n` and never later than `within`, so a quiet feed still answers |
| `Stream<E, A>.buffer(size: Int): Stream<E, A>` | room for `size` elements between a fast producer and a slow consumer, backpressuring when full |
| `Stream<E, A>.buffer(size: Int, strategy: OverflowStrategy): Stream<E, A>` | room between a fast producer and a slow consumer, and what to do when it fills *(Pekko)* |
| `Stream<E, A>.alsoTo(to: Sink<A, *>)` / `.wireTap(to: Sink<A, *>)` | every element to `to` as well; `alsoTo`'s backpressure is the pipeline's, `wireTap`'s is dropped from *(Pekko)* |
| `Stream<E, A>.merge(other: Stream<E2, A>): Stream<E, A>` | both feeds as one, in whatever order they arrive, for `E2 : E` |
| `Stream<E, A>.mergeAll(vararg others: Stream<E2, A>): Stream<E, A>` | `merge` over as many as there are |
| `Stream<E, A>.interleave(other: Stream<E2, A>, segmentSize: Int): Stream<E, A>` | `segmentSize` elements from each in turn |
| `Stream<E, A>.zipWith(other: Stream<E2, B>, f: (A, B) -> C): Stream<E, C>` | one element from each, combined; ends when either side does |
| `Stream<E, A>.zip(other: Stream<E2, B>): Stream<E, Pair<A, B>>` | `zipWith(other, ::Pair)`, into Kotlin's `Pair`, which destructures |
| **The split in `Either`** | |
| `Stream<E, A>.either(): Stream<Nothing, Either<E, A>>` | the failure as the last element, leaving none in the type |
| `Stream<E, Either<L, R>>.absolve(): Stream<E, R>` | back again: a `Left` fails the stream, for `L : E` |
| `Stream<E, Either<L, R>>.divertLefts(to: Sink<L, *>): Stream<E, R>` | every `Left` to that sink, every `Right` onwards without a predicate or a cast *(Pekko)* |
| **Leaving a failure type** | |
| `Stream<E, A>.catchAll(f: (E) -> Stream<E2, A>): Stream<E2, A>` | the declared failure handled, and a defect still dying |
| `Stream<E, A>.orElse(other: Stream<E, A>): Stream<E, A>` | `other` on a failure, not on an empty stream |
| `Stream<E, A>.mapError(f: (E) -> E2): Stream<E2, A>` | the declared failure said in another vocabulary, without saying recovery |
| `Stream<E, A>.restartOnDefect(schedule: Schedule<Throwable, *>): Stream<E, A>` | on a defect, the same description run again after the schedule's delay, with a warn line each time; a declared failure passes through, and a schedule that is done lets the defect through as `Died` |
| `Stream<E, ByteChunk>.decodeUtf8(): Stream<E, String>` | chunks as UTF-8 text, a character split across chunks decoded once both have come; what is not UTF-8 is U+FFFD |
| `Stream<E, String>.encodeUtf8(): Stream<E, ByteChunk>` | each string as UTF-8, one chunk per string |
| `Stream<E, String>.lines(): Stream<E, String>` | text as lines, ended by `\n` or `\r\n` however it was split; the last needs no ending |
| `Stream<E, ByteChunk>.framed(): Stream<E, ByteChunk>` | each chunk as a frame: its length as a 4-byte big-endian `Int`, then the chunk, as `DataOutputStream.writeInt` and `write` put them |
| `Stream<E, ByteChunk>.unframed(maxSize: Int = 16 MiB): Stream<E, ByteChunk>` | the frames `framed` wrote, one element each, however the bytes were split; a length over `maxSize`, or an end partway through a frame, is a defect |
| **Running** | |
| `Stream<E, A>.runCollect(): Run<E, List<A>>` | a run described, collecting every element |
| `Stream<E, A>.runFold(zero: R, f: (R, A) -> R): Run<E, R>` | a run described, folding into `R` |
| `Stream<E, ByteChunk>.runInto(out: OutputStream): Run<E, Long>` | a run described, writing each chunk to `out` and answering how many bytes it wrote |
| `Stream<E, A>.runWith(sink: Sink<A, CompletionStage<M>>): Run<E, M>` | a run described, to the sink named; the sink's materialised value is the run's. `M : Any`, and a sink that materialises `null` anyway is `Died`, never `Done(null)` *(Pekko)* |
| `Run<E, R>.run(backend: StreamBackend): CompletionStage<Exit<E, R>>` | the one call that materialises, on the backend it names |
| `Run<E, R>.start(backend: StreamBackend): Running<E, R>` | the same run with a handle: `exit` is what `run` answers, `stop()` ends it now as `Done` with what the sink has, and `close()` stops and waits. A source that drains, such as a Kafka consumer, is stopped by draining, so what it already sent still reaches the end |
| `Run<E, R>.run(system: ClassicActorSystemProvider): CompletionStage<Exit<E, R>>` | `run(PekkoStreams(system))` *(Pekko)* |
| `Run<E, R>.start(system: ClassicActorSystemProvider): Running<E, R>` | `start(PekkoStreams(system))` *(Pekko)* |
| `Fail<E>.awaitExit(stage: CompletionStage<Exit<E, R>>): R` | the run waited for inside a `Fail`: `Done` is the value, `Failed` fails, `Died` throws. In `surefold-stream-pekko`, but it waits for a run on any backend |
| **Pipes** | |
| `Pipe.from(flow: Flow<In, Out, NotUsed>): Pipe<Nothing, In, Out>` | the way in from Pekko's `Flow` *(Pekko)* |
| `Pipe.identity(): Pipe<Nothing, A, A>` | the pipe that changes nothing, where a chain starts |
| `Pipe<E, In, Out>.via(next: Pipe<E2, Out, Out2>): Pipe<E, In, Out2>` | two pipes as one, the narrower failure slotting in for `E2 : E` |
| `Stream<E, A>.via(pipe: Pipe<E2, A, B>): Stream<E, B>` | the pipe spliced into the stream, for `E2 : E` |
| `Pipe<Nothing, In, Out>.toFlow(): Flow<In, Out, NotUsed>` | Pekko's own `Flow`, once nothing is left to declare *(Pekko)* |
| every operator above | on `Pipe` too, in two forms: `Pipe.map(f)` starts a pipe and `pipe.map(f)` carries one on. `Stream`'s are `via` of them, so there is one implementation of each |
| **The way out** | |
| `Stream<Nothing, A>.toSource(): Source<A, NotUsed>` | Pekko's own `Source`, once nothing is left to declare *(Pekko)* |
| `Stream<Nothing, A>.toSequence(backend: StreamBackend, buffer: Int = 16): Sequence<A>` | a Kotlin `Sequence`, one run on `backend` per iteration, iterable once; see [Pulled as a sequence](#pulled-as-a-sequence) |

## A body that must block

`mapAsync` is for a body that answers a `CompletionStage`. `mapPar` is for one
that cannot: a JDBC call, a blocking client, anything whose answer arrives by
returning. Each element in flight gets a virtual thread of its own, at most
`parallelism` at a time, and the body runs in the stream's `Fail<E>`, so
`fail`, `bind` and `parZip` are all available in it:

<!-- mappar-example -->
```kotlin
import dev.surefold.fail.Either
import dev.surefold.fail.bind
import dev.surefold.stream.Stream
import dev.surefold.stream.from
import dev.surefold.stream.mapParOrFail

data class Row(val id: Int, val customer: String?)

data class Customer(val id: Int, val name: String)

data class NoCustomer(val id: Int)

// A lookup with no stage to hand back: it answers by returning, so reaching it means blocking.
class Directory {
    fun lookup(id: Int): Either<NoCustomer, Customer> = Either.Right(Customer(id, "ada"))
}

val directory = Directory()
val rows = listOf(Row(1, "ada"), Row(2, "grace"))

val customers: Stream<NoCustomer, Customer> =
    Stream.from(rows).mapParOrFail(4) { row -> directory.lookup(row.id).bind() }   // a virtual thread per element in flight
```

`mapPar` keeps the failure type the stream already has, `Nothing` included,
so a body that cannot fail needs nothing spelled out. On a stream that has not
declared a failure yet, `mapParOrFail` infers one from the body, as `mapOrFail`
does. The two cannot share a name, because Kotlin fixes the type before it
reads the body.

A blocked virtual thread parks and frees its carrier for another, so four
elements in flight cost four virtual threads, not four platform threads. A
fail is the stream's declared failure, anything thrown is `Died(cause)`, and a
body still running when the stream is torn down is interrupted where it
blocked.

### When order does not matter

`mapPar` and `mapAsync` answer in the input's order, so one slow call holds
back every answer behind it. Where nothing downstream needs that order, such as
independent writes or a fan-out to a sink, `mapParUnordered` and
`mapAsyncUnordered` pass each answer on as soon as it is ready. At most
`parallelism` calls are in flight in either case, so the backpressure is the same:

<!-- unordered-example -->
```kotlin
import dev.surefold.stream.Stream
import dev.surefold.stream.from
import dev.surefold.stream.mapParUnordered

data class Record(val id: Int)

data class Receipt(val id: Int)

class Store {
    fun write(record: Record): Receipt = Receipt(record.id)
}

val store = Store()

val receipts: Stream<Nothing, Receipt> =
    Stream.from(listOf(Record(1), Record(2))).mapParUnordered(64) { record -> store.write(record) }
```

On Forks and Pekko, answers really do come in completion order. Actors and
TestStreams keep the input's order, which is one of the orders an unordered
operator may answer in.

### A limit nobody has to pick

`parallelism` is a number somebody has to choose, and the right one depends on
a dependency whose capacity changes under load. A `Limit` can be passed
instead. `Limit.fixed(n)` behaves as `n` does. `Limit.adaptive` finds its own
level from how long calls take: it grows while they take no longer than they do
unloaded, and drops as soon as they start queueing. One limit shared by every
caller of a dependency bounds them all together:

<!-- limit-example -->
```kotlin
import dev.surefold.Limit
import dev.surefold.stream.Stream
import dev.surefold.stream.from
import dev.surefold.stream.mapPar

data class Order(val id: Int)

class OrdersDb {
    fun write(order: Order): Int = order.id
}

val ordersDb = OrdersDb()
val orders = Limit.adaptive("orders-db", initial = 20, max = 1_000)

val written: Stream<Nothing, Int> =
    Stream.from(listOf(Order(1), Order(2))).mapPar(orders) { order -> ordersDb.write(order) }
```

The same limit goes to `parMap(ids, orders) { … }` and to a policy's
`guard(orders)`, so all three draw on one budget. It publishes
`surefold.limit.current` and `surefold.limit.in_use`, so a dashboard shows where it
settled.

## Bytes in and out

A stream of `Byte` would box every byte, so bytes travel as `ByteChunk`s: blocks
from core `surefold` that nothing can change once made. `Stream.read` opens an
`InputStream` once per run and closes it however the run ends, and `runInto`
writes each chunk as it arrives:

<!-- bytes-example -->
```kotlin
import dev.surefold.stream.Run
import dev.surefold.stream.Stream
import dev.surefold.stream.decodeUtf8
import dev.surefold.stream.drop
import dev.surefold.stream.lines
import dev.surefold.stream.read
import dev.surefold.stream.runCollect
import dev.surefold.stream.runInto
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

val out = ByteArrayOutputStream()

val copied: Run<Nothing, Long> =
    Stream.read({ Files.newInputStream(Path.of("orders.csv")) }, chunkSize = 64 * 1024)   // Stream<Nothing, ByteChunk>
        .runInto(out)                                                                      // the bytes it wrote

val rows: Run<Nothing, List<String>> =
    Stream.read({ Files.newInputStream(Path.of("orders.csv")) })
        .decodeUtf8()   // a character split between two reads is decoded once both have come
        .lines()        // so is a line
        .drop(1)        // the header
        .runCollect()
```

Text needs no buffering of its own: `decodeUtf8` holds the bytes of a character
a read ended inside until the next read brings the rest, and `lines` holds the
line so far until a `\n` ends it.

A protocol that sends messages over one connection needs to know where each
ends. `framed` writes each chunk after its length, and `unframed` reads them
back as one element per message, however the reads split them:
`Stream.read({ socket.getInputStream() }).unframed()` on one side and
`messages.framed().runInto(socket.getOutputStream())` on the other.

A read that throws is a defect, as from any blocking source, and `stop()`
closes the `InputStream` to reach a read that is blocked, since
`InputStream.read` ignores an interrupt. `runInto` leaves `out` open: whoever
opened it closes it.

## Missing is a failure, not an empty stream

A stage that completed with `null`, an empty `Optional`, a lookup that found
nothing: a builder that reads absence as emptiness turns a miss into a source
with no elements, and a run over it answers `Done` having processed nothing,
which is also what a run with nothing to process answers. Surefold's builders make
the absence a failure with a name instead:

<!-- missing-example -->
```kotlin
import dev.surefold.stream.Stream
import dev.surefold.stream.fail
import dev.surefold.stream.from
import dev.surefold.stream.fromStage
import dev.surefold.stream.orFailIfEmpty
import dev.surefold.stream.single
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.javadsl.Source
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

data class Customer(val id: Int, val name: String)

data class Missing(val id: Int)

// A directory that answers with a customer or with nothing at all, which is the shape of every lookup.
fun lookup(id: Int): Customer? = if (id == 1) Customer(1, "ada") else null

val id = 1
val stage: CompletionStage<Customer> = CompletableFuture.completedFuture(Customer(id, "ada"))
val rows: Source<Customer, NotUsed> = Source.empty()                    // a source surefold did not build

val one: Stream<Missing, Customer> =
    lookup(id)?.let { Stream.single(it) } ?: Stream.fail(Missing(id))   // absence is a failure with a name

val everything: Stream<Missing, Customer> =
    Stream.from(rows).orFailIfEmpty(Missing(id))                        // zero elements → Failed(Missing(id))

val answered: Stream<Nothing, Customer> = Stream.fromStage(stage)       // a null completion → Died(NullPointerException)

val declared: Stream<Missing, Customer> =
    Stream.fromStage(stage, ifNull = Missing(id))                       // a null completion → Failed(Missing(id))
```

- `single` and `of` take `A : Any`, so a nullable is refused at compile time,
  and the `?:` that names the failure is written at the lookup.
- `fromStage` checks at runtime, because a stage from Java can complete with
  `null` whatever its type argument says. The first form dies with a
  `NullPointerException` naming the builder; the second fails with the error
  given.
- `orFailIfEmpty` is for a source Surefold did not build: zero elements is all
  there is to go on, so saying it is a failure is a decision for the caller.
- There is no `fromNullable`, because a builder that turns absence into
  emptiness is the mistake this avoids.

The element bound applies throughout. `Stream.from(rows).map { row -> row.customer }`,
where `customer` is a `String?`, does not compile:

```
e: Return type mismatch: expected 'Any', actual 'String?'.
```

`DoesNotCompileTest` compiles that line, and the `toSource()` case below, on
every build and asserts the compiler's own message.

Code that calls Pekko's absence-to-emptiness builders directly never enters
Surefold. detekt's `ForbiddenMethodCall` can catch it, with type resolution on
(`detektMain` rather than `detekt`). This repository uses the list in
[`config/detekt/detekt.yml`](../config/detekt/detekt.yml), which you can copy:

```yaml
ForbiddenMethodCall:
  active: true
  methods:
    - reason: 'A stage that completes with null emits nothing: Stream.fromStage names the absence.'
      value: 'org.apache.pekko.stream.javadsl.Source.completionStage'
    - reason: 'The older name for the same builder, and the same null completion.'
      value: 'org.apache.pekko.stream.javadsl.Source.fromCompletionStage'
    - reason: 'The Scala half of it: a Future completed with null emits nothing either.'
      value: 'org.apache.pekko.stream.javadsl.Source.future'
    - reason: 'The older name for the Future form, with the same completion.'
      value: 'org.apache.pekko.stream.javadsl.Source.fromFuture'
    - reason: 'A java.util.stream over ofNullable or an empty Optional is a source of no elements.'
      value: 'org.apache.pekko.stream.javadsl.Source.fromJavaStream'
    - reason: 'An iterator over an empty Optional is a source of no elements.'
      value: 'org.apache.pekko.stream.javadsl.Source.fromIterator'
```

The name alone is the match, so every overload is covered. Pekko's javadsl has
no `Source.from(Optional)`: an `Optional` reaches a source through
`fromJavaStream` over `Optional.stream()` or through `fromIterator`, which is
what those two entries are for.

## How a failure travels

`fail(e)` or a `bind()` on a `Left` ends a stream with the `E` it
names. In an element body, `Failing<E>` is a Surefold `Fail<E>`, so anything a
Surefold handler writes, `ensure`, `parZip`, a fork awaited, works there too.
Every other throwable is a defect and arrives as `Exit.Died(cause)`.

There is no supervision strategy and no `resume`, so an element leaves a
pipeline through a named sink, a `filter`, or not at all. To recover, say
what a failure means with `catchAll`, `orElse` or `mapError`; to retry after a
defect, `restartOnDefect(schedule)` runs the description again after each of
the schedule's delays.

A `Died` is also logged at error level before the run returns it, so a
pipeline run only for its effects still reports what happened. The line names
the operator, the element it was processing and the line of your code that
built the operator:

```
[ERROR] surefold-stream: mapAsync died on Row(id=3, customer=null), built at IngestService.kt:41: java.lang.IllegalStateException: ledger down
```

The cause carries the same three facts. Where your lambda threw or a stage
failed, they are attached as a suppressed exception, so `Died` keeps the class
your code threw and `is IllegalStateException` still matches. Where the
library raised the error itself, as for a null completion, they are its
message.

On Pekko, a declared failure rides Pekko's own failure channel inside a
private wrapper with no stack trace that only the run unwraps, so Pekko's
fusion and backpressure are unaffected.

## One publisher, many readers

A `Hub` hands one publisher's elements to every reader in the same process, on
any backend:

```kotlin
val hub = Hub<ShopEvent>(capacity = 256)

hub.publish(event)                        // Either<HubRefused, ShopEvent>: never waits
hub.subscribe().runFold(Tally()) { t, e -> t + e }.start(Forks())
hub.close()                               // every subscription ends Done, after what it was sent
```

- Each subscriber has its own queue, `capacity` deep. `publish` gives an
  element to every subscriber or, when one of them is full, to none, and
  answers `Full`. A publisher that must not lose elements keeps the element and
  offers it again, and a slow reader never silently misses one.
- A subscriber is one run of `subscribe()`, from when it starts until it ends,
  so one that has stopped no longer holds the hub back.
- What is published before anyone subscribes is held, up to `capacity`, for
  the first subscriber. After that, an element published while nobody
  subscribes goes nowhere.
- On `TestStreams` a waiting subscription gives up its turn, so a test can
  publish and see the run read it without moving the clock.

## Pulled as a sequence

Code that pulls, such as Pelican's Jetty backend writing a response from a
`Sequence`, takes a stream through `toSequence`:

```kotlin
events streamedNow { watch().toSequence(streams) }
```

- Iterating starts one run on the backend named. A second iteration throws.
- At most `buffer` elements wait between the run and the reader, so a reader
  that stops pulling holds the run back.
- The iterator is `AutoCloseable`. Closing it, from any thread, stops the run
  and returns once the run has let go. Pelican's Jetty writer closes it however
  the response ends.
- A run that dies throws its cause from the reader's next `hasNext()`, after
  the elements it sent. A run stopped by closing throws nothing.
- Only `Stream<Nothing, A>` has it, as with `toSource()`. On `TestStreams`,
  which runs on the caller's thread, a stream longer than its buffer holds the
  reader there.

## Optimisation, rendering and profiling

Before a run starts, adjacent element-at-a-time stages (`map`, `filter`,
`filterNot`, `mapOrFail`) are fused into one, `take(a).take(b)` becomes one
`take`, `drop(a).drop(b)` one `drop`, and a `catchAll`, `orElse` or
`mapError` over a stream that cannot fail is removed. A defect still names the
original operator and line. On Pekko, fusion took a five-stage chain from about
150 ns to 91 ns per element.

`render()` draws a stream or a run as text or Mermaid, with each stage and the
line that built it, and `render(optimised = true)` draws what will actually
run:

```
Stream.from
fused[map, map, filter, map, mapOrFail]  Pipelines.kt:15
runFold                                  Pipelines.kt:20
```

`run.measured(Measured("ingest"))` reports each stage's element count, the
time spent in its body and the time it waited for demand through Surefold's
[metrics](observability.md#metrics), as `surefold.stream.elements`,
`surefold.stream.busy` and `surefold.stream.waiting`. A `Profiler` keeps those
numbers, and `render(profile = profiler.profile())` shows where the time went:

```
map          Pipelines.kt:15  3% · 1.0 µs busy · 3.0 µs waiting · 100000 out
map          Pipelines.kt:16  67% · 20.0 µs busy · 3.0 µs waiting · 100000 out
filter       Pipelines.kt:17  3% · 1.0 µs busy · 3.0 µs waiting · 50000 out
```

One profiler can measure several pipelines. It keeps each apart, and
`profiler.profile("ingest")` names the one to show. Asked for `profile()` with
no name, it refuses once it has seen more than one, rather than sum them.

## Time a test owns

`tick`, `groupedWithin`, `restartOnDefect` and `mapPar` wait on time or on
other threads. On `TestStreams`, from `surefold-stream-test`, they wait only on a
`TestClock`, so a test moves time instead of sleeping through it:

```kotlin
val clock = TestClock()
val running = Stream.tick(1.minutes, "t").runCollect().start(TestStreams(clock))

clock.adjust(1.hours)
running.emitted().size shouldBe 60
```

One stage runs at a time, in the same order every time, and `mapPar` handles
one element at a time. `start` returns once the run is over or waiting on a
later time. Each `adjust` stops at every instant the run waits for on the way,
so what falls due runs in time order before `adjust` returns. `emitted()`
reads what a collecting run on `TestStreams` has produced so far. A stage body
that reads Surefold's `clock` reads the test's; a body that blocks on anything
else blocks the test with it.

## On Pekko Streams

`surefold-stream-pekko` is both a backend and a typed layer over Pekko's own
API. `Stream.from(source)` and `toSource()` convert in and out, `runWith(sink)`
runs into a Pekko `Sink`, `divertLefts(to = sink)` splits a stream of `Either`
into two sinks, and `run(system)` is short for `run(PekkoStreams(system))`.
`toSource()` exists only on `Stream<Nothing, A>`, so a stream that can still
fail has to say what a failure means, with `catchAll`, `either` or `orElse`,
before it can become a plain Pekko `Source`.

### What it changes

A Kotlin service written directly against Pekko's `javadsl` has one error
channel typed `Throwable` for the whole graph, and a `Supervision.Decider` set
as an attribute some distance from the operator it governs. A team that keeps
its failures in an `Either` puts one in every element, splits the stream
with `divertTo(sink, predicate)` and casts the survivors back. Two things can
then drop an element without a trace, and Pekko documents both: a `mapAsync`
whose `CompletionStage` completes with `null` is not passed downstream, and a
`Resume` decider drops the element and continues. The first is easy to write
in Kotlin, as a nullable lookup lifted into a future, and nothing in the types
warns about it.

The same ingest written both ways, from shared fixtures:

<!-- example-fixtures -->
```kotlin
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.fold
import dev.surefold.fail.mapLeft
import dev.surefold.stream.Stream
import dev.surefold.stream.divertLefts
import dev.surefold.stream.from
import dev.surefold.stream.mapAsync
import dev.surefold.stream.mapOrFail
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.javadsl.Sink
import org.apache.pekko.stream.javadsl.Source
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

data class Row(val id: Int, val customer: String?)

data class Customer(val id: Int, val name: String)

data class Receipt(val id: Int)

sealed interface IngestError

data class NoCustomer(val id: Int) : IngestError

data class Declined(val id: Int) : IngestError

class Ledger {
    fun settle(customer: Customer): CompletionStage<Either<Declined, Receipt>> =
        CompletableFuture.completedFuture(Either.Right(Receipt(customer.id)))
}

val ledger = Ledger()
val rows = listOf(Row(1, "ada"), Row(2, "grace"), Row(3, null))
val declinedSink = Sink.foreach<Declined> { declined -> println("declined ${declined.id}") }
```

Raw Pekko `Source`:

<!-- before-example -->
```kotlin
val receipts: Source<Either<IngestError, Receipt>, NotUsed> =
    Source.from(rows)
        .map { row ->                                        // Source<Either<IngestError, Customer>, NotUsed>
            attempt<IngestError, Customer> { Customer(row.id, row.customer ?: fail(NoCustomer(row.id))) }
        }
        .mapAsync(4) { customer ->
            customer.fold(
                { e -> CompletableFuture.completedFuture<Either<IngestError, Receipt>>(Either.Left(e)) },
                { c -> ledger.settle(c).thenApply { it.mapLeft { d -> d as IngestError } } },
            )                                                // the Left carried past a stage that never wanted it,
        }                                                    // and Declined widened to IngestError by hand
        .divertTo(
            declinedSink.contramap<Either<IngestError, Receipt>> { (it as Either.Left).value as Declined },
            { it is Either.Left && it.value is Declined },    // a cast for the sink, a predicate on the subtype
        )                                                    // and a NoCustomer Left is still in the stream
```

With `surefold-stream`:

<!-- after-example -->
```kotlin
val receipts: Stream<IngestError, Receipt> =
    Stream.from(rows)
        .mapOrFail { row -> Customer(row.id, row.customer ?: fail(NoCustomer(row.id))) }   // a nullable body does not compile
        .mapAsync(4) { customer -> ledger.settle(customer) }                                // the same mapAsync, E already on the stream
        .divertLefts(to = declinedSink)                                                     // no predicate, no cast
```

Raw Pekko carries the failure in the element, so every stage after the first
unwraps and rewraps one: the `mapAsync` that only wanted a customer carries a
`Left` past itself, and `Declined` is widened to `IngestError` by hand. A
diverted element and a failed pipeline then share one channel, told apart by a
predicate on the error's subtype, so a `NoCustomer` that should end the run
keeps flowing to the consumer, and the element type at the end is still
`Either<IngestError, Receipt>`.

With `surefold-stream` the failure is on the stream, not in the element:
`mapAsync` sees a `Customer` and passes on the ledger's own `Either`,
`divertLefts` splits on that without a predicate, and what reaches the end is
a `Receipt`. Pekko runs the same three stages either way; what changes is
what the types say and what they will not let you write.

### A complete pipeline

Rows in, receipts to one sink, declines to another, and the run's `Exit`
turned back into an `Either` in the handler that started it:

<!-- readme-example -->
```kotlin
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.stream.Stream
import dev.surefold.stream.awaitExit
import dev.surefold.stream.divertLefts
import dev.surefold.stream.from
import dev.surefold.stream.mapAsync
import dev.surefold.stream.mapOrFail
import dev.surefold.stream.run
import dev.surefold.stream.runWith
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.javadsl.Sink
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

data class Row(val id: Int, val customer: String?)

data class Customer(val id: Int, val name: String)

data class Receipt(val id: Int)

sealed interface IngestError

data class NoCustomer(val id: Int) : IngestError

data class Declined(val id: Int) : IngestError

class Ledger {
    fun settle(customer: Customer): CompletionStage<Either<Declined, Receipt>> =
        CompletableFuture.completedFuture(Either.Right(Receipt(customer.id)))
}

val system: ActorSystem = ActorSystem.create("ingest")
val ledger = Ledger()
val rows = listOf(Row(1, "ada"), Row(2, "grace"), Row(3, null))

// Where a decline goes and where a receipt goes: two sinks with names, rather than a decider that drops one.
val declinedSink = Sink.foreach<Declined> { declined -> println("declined ${declined.id}") }
val receiptSink = Sink.foreach<Receipt> { receipt -> println("receipt ${receipt.id}") }

val settled: Either<IngestError, Done> = attempt {
    awaitExit(
        Stream.from(rows)                                              // Stream<Nothing, Row>
            .mapOrFail { row ->                                        // Stream<IngestError, Customer>
                Customer(row.id, row.customer ?: fail(NoCustomer(row.id)))
            }
            .mapAsync(4) { customer -> ledger.settle(customer) }        // up to four stages at once, in order
            .divertLefts(to = declinedSink)                            // Stream<IngestError, Receipt>
            .runWith(receiptSink)                                      // Run<IngestError, Done>
            .run(system),                                              // CompletionStage<Exit<IngestError, Done>>
    )                                                                  // Done → the value, Failed → fail, Died → throw
}
```

`awaitExit` waits for the run inside a `Fail`: `Done` is the value, `Failed`
fails, and `Died` throws. It waits through `surefold-pekko`'s
[`await`](core.md#running-forks-on-pekko), so an interrupt cancels the run
rather than abandoning it.

## Dependencies

`surefold-stream` puts only the Kotlin standard library and `surefold` on
a consumer's classpath: a description names no backend. `surefold-stream-forks`
and `surefold-stream-test` add nothing beyond Surefold modules, and
`surefold-stream-actors` adds `surefold-actor`. `surefold-stream-pekko` adds `surefold-pekko`
and `pekko-stream`, with the Scala runtime, Typesafe Config, the Reactive
Streams interfaces and `ssl-config-core` that Pekko brings. Each module's
`NoOtherDependenciesTest` checks this list against its real runtime classpath.
