# Testing

Surefold's test support sits with the thing it tests. This page collects it in one
place and links to the detail. Most of it shares one idea: time and context
are values bound for a block, so a test can replace the clock, the logger or
the metrics registry without touching the code under test, and nothing has to
sleep.

| Need | Use | Module |
|---|---|---|
| a backoff, timeout or timer that does not wait | `clock.locally(fixedClock()) { }` or a `TestClock` | `surefold` |
| assertions on log lines or metrics | `capturingLogs { }`, `capturingMetrics { }` | `surefold` |
| a service test written as steps | `story { Given / When / Then }` | `surefold-test` |
| waiting for something to become true | `eventually(within) { }` | `surefold-test` |
| checks that name the test's line on failure | `shouldBe`, `shouldBeRight`, `withClue { }` | `surefold-assert` |
| tests that run concurrently | `object … : Suite()` | `surefold-junit` |
| tests that share a resource and run concurrently | `object … : SharedSuite<R>()` | `surefold-junit` |
| one test for each of a list of cases | `testEach(name, cases) { case -> }` | `surefold-junit` |
| a story as a suite's test, returning its expectations | `story { }` | `surefold-junit` |
| one class | its constructor, with fakes as arguments | nothing |
| several nodes together, one faked | `testApp(module.subgraph<Root>().overriding(…)) { }` | `surefold-app` |
| configuration for one test | `overridingConfig("key = value")` | `surefold-app-typesafe` (surefold-integrations) |
| a stream on a clock the test moves | `TestStreams(clock)` and `emitted()` | `surefold-stream-test` |
| actors stepped on the calling thread | `behaviour.test()`, `testActors { }` | `surefold-actor` |

The Gradle plugin's compiler checks also warn about `Thread.sleep` and
`TimeUnit.sleep` in test code (`SUREFOLD_SLEEP_IN_TEST`); see
[Applications](app.md#what-the-compiler-plugin-warns-about).

## Time

`clock` is a [`SurefoldLocal`](core.md#what-a-fork-inherits), and every wait in
Surefold goes through it: `Schedule` delays, `timeout`, probe retries, actor
timers and the time-based stream operators.

- **`fixedClock()`** never advances, and a sleep on it returns at once. A
  five-retry exponential backoff runs in microseconds, and every log line is
  stamped with the same instant. Because `timeout` also sleeps on the clock, a
  `timeout` under `fixedClock` gives up immediately.
- **`TestClock()`** moves only when the test calls `adjust(by)` or
  `setTime(to)`. Each `adjust` releases every sleeper due by the new time, in
  time order. `adjustWhenBlocked(by)` first waits until every sleeping fork is
  waiting on a time still in the future, because a virtual thread gives no
  signal that it has reached its `sleep`.

<!-- testing-clock -->
```kotlin
import dev.surefold.Schedule
import dev.surefold.clock
import dev.surefold.fixedClock
import dev.surefold.retry
import kotlin.time.Duration.Companion.seconds

val backoff = Schedule.exponential<Throwable>(1.seconds) and Schedule.recurs(5)

var attempts = 0

fun flakyRow(): String = if (++attempts < 4) error("not yet") else "row"

fun withoutWaiting(): String = clock.locally(fixedClock()) { backoff.retry { flakyRow() } }   // no real waiting
```

## Logs and metrics

`capturingLogs` and `capturingMetrics` bind an in-memory logger or registry
for a block, so a claim about logging or measuring is an assertion about
values:

<!-- testing-capture -->
```kotlin
import dev.surefold.LogLine
import dev.surefold.capturingLogs
import dev.surefold.capturingMetrics
import dev.surefold.clock
import dev.surefold.counter
import dev.surefold.fixedClock
import dev.surefold.increment
import dev.surefold.logAnnotated
import dev.surefold.logInfo
import dev.surefold.parMap

fun linesLogged(): List<LogLine> = capturingLogs { logs ->
    logAnnotated("correlation_id" to "abc-123") { logInfo("registered") }
    logs.all()
}

fun adoptionsCounted(): Double = capturingMetrics { measured ->
    counter("petshop.adoptions").increment()
    measured.counter("petshop.adoptions")
}

// Under fixedClock every line is stamped with the same instant, and nothing waits.
fun stampedTogether(): List<LogLine> = clock.locally(fixedClock()) {
    capturingLogs { logs ->
        parMap(listOf(1, 2, 3)) { logInfo("branch $it") }
        logs.all()
    }
}
```

Both are in the core `surefold` module.

A surefold-junit test runs with its logger and registry bound to a capture, so
`logger.get()` inside one is never what `main` gets. `logger.unbound()` is:
the logger the classpath registered, or `StderrLogger`. To run a block as
`main` would log, bind that back with `logger.locally(logger.unbound()) { … }`.

## Stories and waiting

`surefold-test` is for tests of a whole service. `story { }` writes a test as
`Given`, `When`, `Then`, `And` and `But` steps that return values. When a step
fails, the message shows the story up to that step and what it threw, in
colour under IntelliJ or with `FORCE_COLOR` set (`NO_COLOR` turns it off, and
`-Dsurefold.test.colour=always|never` overrides both). In a `surefold-junit` suite
the story is coloured on the console and plain in the report and the failure.
IntelliJ is detected only when it runs the tests itself; under "Run tests using:
Gradle", set `FORCE_COLOR=1` in the run configuration.

`eventually(within) { }` retries a block every 20 ms by default until it stops
throwing, and gives up with `GaveUp`, an `AssertionError`, once `within` has
passed on the inherited clock. A step can wait the same way with
`Then("…").eventually(within) { }`. In a `surefold-junit` suite, a block whose
checks did not hold is tried again, as one that throws is. When time runs out,
the last try's failures are recorded, each saying the wait gave up, and
`eventually` answers what that try returned, so the test goes on to its next
check.

```kotlin
dependencies {
    testImplementation("dev.surefold:surefold-test:0.18.0")
}
```

```kotlin
import dev.surefold.assert.shouldBe
import dev.surefold.test.story
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class AdoptionTest {

    // `PetShop` belongs to the service
    @Test
    fun `an adopted pet is listed as taken`() = story {
        val shop = Given("a shop with three pets") { PetShop(listOf("Nibbles", "Barnaby", "Mrs Peel")) }
        val taken = When("Ada adopts the first") { shop.adoptFirst("Ada") }
        Then("Ada has Nibbles") { taken shouldBe "Nibbles" }
        And("the listing says so").eventually(within = 5.seconds) { shop.status("Nibbles") shouldBe "taken" }
    }
}
```

`surefold-test` depends only on `surefold`. It brings no assertion library; the
example uses [`surefold-assert`](#assertions)'s, but any assertion that throws works.
In a suite, a story returns its expectations instead: see
[Stories in a suite](#stories-in-a-suite).

## Assertions

`surefold-assert` holds the checks Surefold's own tests use. Each one throws
opentest4j's `AssertionFailedError` when it fails, so any JUnit runner reports
it, and returns the value it checked when it holds. The failure says what was
expected, what was found, and the file, line and source of the check, and its
stack starts at the test's own frame:

```
expected: Right(value=42)
but was:  Left(value=Bad(why=broken))
at ScopeTest.kt:322
    bound shouldBeRight 42
```

| Checks | On |
|---|---|
| `shouldBe`, `shouldNotBe`, `shouldBeNull`, `shouldNotBeNull`, `shouldBeInstanceOf<T>`, `shouldBeSameInstanceAs` | any value |
| `shouldBeRight`, `shouldBeLeft` | Surefold's `Either`, naming the side it found |
| `shouldBeLessThan`, `shouldBeGreaterThanOrEqual`, `shouldBeInRange`, … | anything `Comparable` |
| `shouldContain`, `shouldStartWith`, `shouldEndWith`, `shouldMatch`, `shouldNotBeBlank` | a string, or a `Regex` match within it |
| `shouldContain`, `shouldContainAll`, `shouldContainExactly`, `shouldContainExactlyInAnyOrder`, `shouldContainOnly`, `shouldHaveSize`, `shouldBeEmpty` | a collection |
| `shouldContainKey`, `shouldContain(key to value)`, `shouldContainAll`, `shouldContainExactly` | a map |
| `shouldThrow<T> { }` | a block, returning what it threw |

`shouldBe` and `shouldContainExactly` carry both sides, so an IDE can show
them side by side. `withClue(clue) { }` puts the clue above any failure inside
its block; a clue written as a lambda is only worked out if something fails.

## Suites

`surefold-junit` runs tests on the JUnit Platform with Surefold's own concurrency. A
suite is a Kotlin `object`, which the `surefold` engine finds with no annotation. A
test is registered from `init`, and its result is the [checks](#expectations) it makes.

- **`Suite`:** tests that share nothing, registered with `test(name) { … }`.
- **`SharedSuite<R>`:** tests that share one `R`, registered with
  `test(name) { shared -> … }`. Its `shared` resource is acquired once, handed
  to every test, and released after the last. If acquiring throws, each test
  fails with that throw, and what was installed before it is released.

```kotlin
object SortingSuite : Suite() {
    init {
        test("a list is sorted smallest first") { expect(listOf(3, 1, 2).sorted() == listOf(1, 2, 3)) }
    }
}
```

- **Concurrency:** each test is a fork on a virtual thread, at most the
  suite's `Limit` at once, `Limit.fixed(availableProcessors)` by default.
  Suites run one after another. A test given the [`alone`](#aspects) aspect
  waits for those registered before it, and later ones wait for it;
  `Suite(concurrent = false)` runs every test alone.
- **Logs:** each test logs and measures into a capture of its own. A passing
  test prints nothing. A failed test's lines are printed under its name and
  published to the platform as a `logs` report entry.
- **Reporting:** results go through the JUnit Platform, so Gradle's JUnit XML,
  IntelliJ's test tree and CI read a suite as they read a Jupiter class, and
  the two run side by side in one `test` task. A test's name is its display
  name and its ID, so two tests in a suite cannot share one. IntelliJ gives
  each suite a run icon of its own; a single test is rerun from the results
  tree, as its tests are calls rather than methods.

```kotlin
dependencies {
    testImplementation("dev.surefold:surefold-junit:0.18.0")
}

tasks.test { useJUnitPlatform() }
```

<!-- testing-junit-suite -->
```kotlin
import dev.surefold.Limit
import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.alone
import dev.surefold.junit.expect
import dev.surefold.logInfo
import dev.surefold.resource
import java.util.concurrent.ConcurrentHashMap

// Stands in for the service under test.
class PetShop {
    private val pets = ConcurrentHashMap<String, String>()

    fun list(name: String) {
        pets[name] = "available"
    }

    fun status(name: String): String? = pets[name]

    fun count(): Int = pets.size

    fun close() = pets.clear()
}

object PetShopSuite : SharedSuite<PetShop>(limit = Limit.fixed(8)) {
    override val shared: Resource<PetShop> = resource { install({ PetShop() }) { shop, _ -> shop.close() } }

    init {
        test("a listed pet is available") { shop ->
            logInfo("listing Nibbles")                      // printed only if this test fails
            shop.list("Nibbles")
            expect(shop.status("Nibbles") == "available")
        }
        test("an unlisted pet has no status") { shop -> expect(shop.status("Mrs Peel") == null) }
        test("one pet is listed", alone) { shop -> expect(shop.count() == 1) }   // after the tests above
    }
}
```

### More than one resource

A suite has one `shared` resource, but it can hold several things. Write each
as its own `Resource`, and build the suite's from them in one `resource { }`
block, where `.bind()` acquires each one. They are released in reverse order,
and if one fails to start, those already started are released. Something a
single test needs fresh is acquired in that test, with `use`, and released
when the test ends.

<!-- testing-junit-resources -->
```kotlin
import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.resource
import dev.surefold.use
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// Stand in for a database and a broker: each started once for the suite, closed after its last test.
class Orders {
    private val saved = ConcurrentHashMap<String, Int>()

    fun save(id: String, count: Int) {
        saved[id] = count
    }

    fun count(id: String): Int = saved[id] ?: 0

    fun close() = saved.clear()
}

class Broker {
    private val sent = CopyOnWriteArrayList<String>()

    fun publish(event: String) {
        sent += event
    }

    fun published(prefix: String): List<String> = sent.filter { it.startsWith(prefix) }

    fun close() = sent.clear()
}

// A recorder each test gets fresh: what one test saw, and nothing another test did.
class Recorder {
    val seen = CopyOnWriteArrayList<String>()

    fun close() = seen.clear()
}

// Each piece is its own Resource, so a suite that needs one alone can take just that one.
fun orders(): Resource<Orders> = resource { install({ Orders() }) { db, _ -> db.close() } }

fun broker(): Resource<Broker> = resource { install({ Broker() }) { broker, _ -> broker.close() } }

fun recorder(): Resource<Recorder> = resource { install({ Recorder() }) { recorder, _ -> recorder.close() } }

class Shop(val orders: Orders, val broker: Broker)

object ShopSuite : SharedSuite<Shop>() {
    // Acquired in order and released in reverse: the broker, then the orders. If the broker fails to start,
    // the orders already started are released, and every test fails with the broker's error.
    override val shared: Resource<Shop> = resource { Shop(orders().bind(), broker().bind()) }

    init {
        test("an order is saved and published once") { shop ->
            shop.orders.save("order-1", 2)
            shop.broker.publish("order-1:placed")
            shop.orders.count("order-1") shouldBe 2
            shop.broker.published("order-1") shouldBe listOf("order-1:placed")
        }
        test("a test that needs its own resource acquires it, and it is released when the test ends") { shop ->
            recorder().use { recorder ->
                shop.broker.publish("order-2:placed")
                recorder.seen += "order-2:placed"
                recorder.seen.toList() shouldBe shop.broker.published("order-2")
            }
        }
    }
}
```

### Suites side by side

Suites run one after another by default, each with its own tests running
together. The `surefold.junit.suites.limit` configuration parameter or system
property lets that many suites run at once. Raise it once your suites are safe
beside each other: no two of them may claim the same port, system property or
file. A suite that must still run alone, such as one that migrates a schema,
says `isolated = true`: it waits for the suites before it, and holds back the
ones after it.

```kotlin
tasks.test {
    useJUnitPlatform()
    systemProperty("surefold.junit.suites.limit", "4")
}
```

Suites side by side would each start their own database, and could open more
connections at once than it allows. A `Guarded` resource is the one copy for
the whole run, declared as an `object`. It is started the first time a test
uses it, released after the last suite, and lent to at most `permits` tests at
once, from every suite together. A `SharedSuite` whose `shared` is its
`lease()` holds a permit in each test for the test's whole body. A plain
suite's test holds one for a block with `use { }`. A test that leases a guard
must not also `use` it, or with one permit left it would wait for itself.

<!-- testing-junit-guarded -->
```kotlin
import dev.surefold.Resource
import dev.surefold.junit.Guarded
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.resource
import java.util.concurrent.ConcurrentHashMap

// Stands in for a database: started once for the whole run, closed after its last suite.
class Database {
    private val rows = ConcurrentHashMap<String, Int>()

    fun save(id: String, total: Int) {
        rows[id] = total
    }

    fun total(id: String): Int = rows[id] ?: 0

    fun close() = rows.clear()
}

// One database for the run, with at most 20 tests on it at once, whichever suites they are in.
object TestDatabase : Guarded<Database>(permits = 20) {
    override val resource: Resource<Database> = resource { install({ Database() }) { db, _ -> db.close() } }
}

object OrdersSuite : SharedSuite<Database>() {
    // Each test holds a permit for its whole body. It waits for one before it starts, so its timeout does not run.
    override val shared: Resource<Database> = TestDatabase.lease()

    init {
        test("an order is saved once") { db ->
            db.save("order-1", 3)
            db.total("order-1") shouldBe 3
        }
    }
}

object InvoicesSuite : Suite() {
    init {
        test("an invoice totals its lines") {
            // The same database from a plain suite, holding a permit for the block only.
            TestDatabase.use { db ->
                db.save("invoice-1", 7)
                db.total("invoice-1") shouldBe 7
            }
        }
    }
}

// Runs alone in the whole run, however many suites run side by side.
object SchemaSuite : Suite(isolated = true) {
    init {
        test("the schema is migrated before anything reads it") {
            TestDatabase.use { db -> db.total("schema-version") shouldBe 0 }
        }
    }
}
```

### State in a suite

A suite is one `object`, so its properties are shared by every test in it, and
by tests running at the same time. Jupiter makes a new instance of the class
for each test, so a field there starts fresh each time; in a suite it does
not. A value a test changes, such as a counter, a list it adds to or an
exception it attaches notes to, belongs inside the test, or in a function the
test calls to make a fresh one. Put what the tests share on purpose in
`shared`.

A test that needs a directory of its own, as Jupiter's `@TempDir` gives one,
calls `withTempDirectory { dir -> … }`. The directory is new and empty, and
it is deleted with everything in it once the block returns or throws.

### A test for each case

`testEach` registers one test for each case in a list, under a container named
for the group, as Jupiter's `@ParameterizedTest` does. The cases are any
Kotlin values; a data class keeps each one readable.

- **Each case is a test.** Each runs on a fork of its own beside the suite's
  other tests, with its own logs and checks, so every case runs and a wrong
  one fails alone. Its failures and logs name it `group [case]`.
- **Names:** a case is named by `named`, `toString()` by default. The name
  is its ID, `[suite:…]/[group:…]/[test:…]`, so an IDE reruns a group or one
  case; two cases with one name, or two groups, are refused.
- **Aspects** given to `testEach` apply to every case, inside the suite's.
- In a `SharedSuite`, the body takes the shared resource and the case,
  `{ shared, case -> }`.

<!-- testing-junit-each -->
```kotlin
import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.tag
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class Parsed(val text: String, val expected: Duration)

object DurationSuite : Suite() {
    init {
        testEach(
            "a duration parses",
            listOf(Parsed("1s", 1.seconds), Parsed("2m", 2.minutes), Parsed("1h30m", 90.minutes)),
            aspects = tag("parsing"),
            named = { it.text },                    // tests "1s", "2m" and "1h30m" under "a duration parses"
        ) { case ->
            expect(Duration.parse(case.text) == case.expected)
        }
    }
}
```

## Expectations

Every check a suite's test makes is recorded as it is made, on a fork or an
actor too, and the record is the test's result. A failed check does not stop
the test or hide the ones after it, and a test that records no check fails.
Checks are written one after another, as statements. `expect(condition)` is the
one check every other is built from. `anyOf({ … }, { … })` holds if any of its
blocks does, and `not { … }` holds if its block fails; the checks inside count
only through them. A check made on a thread surefold does not carry the test's
context to, such as a bare `Thread`, throws there. One failure is reported
alone, and several as one `MultipleFailuresError`.

Built with JetBrains' power-assert plugin, a failed `expect` draws each value
under the expression, then the test's file and line:

```
an order is saved once: 1 of 2 expectations failed
expect(db.count() == 1)
       |  |       |
       |  2       false
       Database(orders=2)
at OrderSuite.kt:32
```

Without the plugin, it quotes the source line instead. A diagram carries no
expected and actual for IntelliJ's diff, so `surefold-junit` also has `shouldBe`,
`shouldNotBe`, `shouldBeRight` and `shouldBeLeft`, which carry both. Beside them are `shouldContain` and `shouldNotContain`, for a
string or a collection, `shouldStartWith`, `shouldEndWith`, `shouldMatch`, a regex
`shouldContain` and `shouldNotBeBlank` for a string, `shouldContainExactly`,
`shouldContainExactlyInAnyOrder`, `shouldContainOnly`,
`shouldContainAll`, for a collection or a map, `shouldHaveSize`, `shouldBeEmpty`
and `shouldNotBeEmpty`, `shouldContainKey` and `shouldNotContainKey`,
`shouldBeNull`, the comparisons `shouldBeLessThan`, `shouldBeLessThanOrEqual`,
`shouldBeGreaterThan`, `shouldBeGreaterThanOrEqual` and `shouldBeInRange`, and
`shouldBeSameInstanceAs` and `shouldNotBeSameInstanceAs`. Four take a lambda
that runs only when they hold: `x.shouldNotBeNull { a -> … }` checks the
value, `x.shouldBeInstanceOf<T> { t -> … }` checks it as a `T`, and
`e.shouldBeRight { a -> … }` and `e.shouldBeLeft { e -> … }` check what an
`Either` holds. `xs.forAll { x -> … }` checks every element, keeping each one's
failures; on no elements it checks nothing. `shouldThrow<T>(block)` holds when `block`
throws a `T`; `shouldThrow<T>(block) { e -> … }` then checks what it threw. A
different exception fails the test with that exception as its cause, and an
interrupt or cancellation goes on up, so a `timeout` still stops the test.
`holds { }` is one check made by a library that checks by throwing, such as
Playwright's `assertThat`: it holds if the block returns, and fails at its line
if the block throws an `AssertionError`.
Anything else it throws errors the test. Every check returns `Unit`, so a
helper that takes a block of checks calls it as a statement. Import these from `surefold-junit`, not `surefold-assert`,
whose matchers throw and return the value; a file importing both can call
neither. A throwing check, such as `surefold-assert`'s, is still allowed in a
test: it stops the test, which is reported as an error.

<!-- testing-junit-expect -->
```kotlin
import dev.surefold.Resource
import dev.surefold.fail.Either
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.expect
import dev.surefold.junit.not
import dev.surefold.junit.shouldBeLeft
import dev.surefold.junit.shouldBeRight
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldThrow
import dev.surefold.resource

data class NotFound(val id: String)

class Database {
    private val orders = mutableListOf<String>()

    fun save(order: String) {
        require(order.isNotBlank()) { "an order needs an id" }
        orders += order
    }

    fun count(): Int = orders.size

    fun find(id: String): Either<NotFound, String> =
        orders.firstOrNull { it == id }?.let { Either.Right(it) } ?: Either.Left(NotFound(id))

    override fun toString(): String = "Database(orders=${orders.size})"
}

object OrderSuite : SharedSuite<Database>(concurrent = false) {
    override val shared: Resource<Database> = resource { Database() }

    init {
        test("an order is saved once") { db ->
            repeat(2) { db.save("order-1") }
            expect(db.count() == 1)
            db.find("order-1") shouldBeRight "order-1"
        }
        test("an order never saved is not found") { db ->
            db.find("order-9") shouldBeLeft NotFound("order-9")
            not { expect(db.count() == 0) }
        }
        test("an order without an id is refused") { db ->
            shouldThrow<IllegalArgumentException>({ db.save("") }) { refusal -> refusal.message shouldContain "an id" }
        }
    }
}
```

The build needs the plugin, with `expect` in its functions, on the test
source set. Nothing else: the engine records each check as it is made, so a
test written `expect(a); expect(b)` counts both, and so does a helper that calls
a check it is handed as a statement.

```kotlin
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.power-assert") version "2.4.10"
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
powerAssert {
    functions.add("dev.surefold.junit.expect")
    includedSourceSets.add("test")
}
```

## Stories in a suite

`surefold-junit` has a `story` of its own, for a suite's test. Each step is a
statement, and each check a step makes counts, on a line of its own or not.
A step whose expectations failed is marked `✗` and the story goes on,
so every failed `Then` is reported; a step that throws still ends the story
with a `StoryFailed`.

- **Title:** an untitled story takes its test's name.
- **Transcript:** it is one of the test's [log lines](#suites), so a passing
  test prints nothing, and a failed test's transcript is printed and published
  with the rest of its `logs`. Outside a suite, `surefold-test`'s `story` prints to
  stdout as before.
- **Imports:** import `story` from `surefold-junit`, which brings `surefold-test` with
  it. `surefold-test`'s `story` returns `Unit`; a file importing both can call
  neither until one is imported under another name.

<!-- testing-junit-story -->
```kotlin
import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.story
import dev.surefold.resource
import java.util.concurrent.ConcurrentHashMap

// Stands in for the service under test.
class Orders {
    private val saved = ConcurrentHashMap.newKeySet<String>()

    fun clear() = saved.clear()

    fun save(order: String) {
        saved += order
    }

    fun count(): Int = saved.size

    fun find(order: String): String? = order.takeIf { it in saved }
}

object OrderStorySuite : SharedSuite<Orders>(concurrent = false) {
    override val shared: Resource<Orders> = resource { Orders() }

    init {
        test("an order is saved once") { orders ->
            story {                                                  // titled "an order is saved once"
                Given("no orders") { orders.clear() }
                When("the order is saved twice") { repeat(2) { orders.save("order-1") } }
                Then("it is stored once") { orders.count() shouldBe 1 }
                Then("it can be found") { orders.find("order-1") shouldBe "order-1" }
            }
        }
    }
}
```

## Aspects

An aspect says how a suite's test runs. It is given beside the test's name,
`test(name, flaky(3)) { }`, or to the suite for each of its tests,
`Suite(aspects = tag("io"))`. Aspects are known when the test is registered,
so discovery reads tags and `ignore` without running anything.

| Aspect | What it does | Reported as |
|---|---|---|
| `flaky(n)` | runs the test up to `n` times, until an attempt passes | passed if one does, with an `attempts` report entry such as `2 of 3`, given on failure too |
| `nonFlaky(n)` | runs the test `n` times, and every run must pass | failed at the first failing run: `run 7 of 50 failed: …` |
| `timeout(d)` | interrupts the test if it runs longer than `d`, after writing every thread's stack, virtual threads included, to stderr | failed with a `TimeoutException`, `no answer within d`; the dump is in the test's output |
| `ignore(why)` | does not run the test | skipped, with `why` |
| `tag(name)` | a JUnit tag, which Gradle's `includeTags` and the IDE filter by | — |
| `alone` | nothing else in the suite runs beside the test | — |

`+` combines aspects, and the left one wraps the right one.
`timeout(5.seconds) + flaky(3)` gives all three attempts five seconds
together, and an attempt cut off by it is not retried;
`flaky(3) + timeout(5.seconds)` gives each attempt five seconds, and retries
one that runs out. A suite's aspects wrap each of its tests'. Tags add up, a
test is `alone` if either side is, and an `ignore` on the left gives its reason
before one on the right.

A test with no `timeout` of its own, nor one from its suite, is held to the
`surefold.junit.timeout.default` configuration parameter or system property, if
set: a duration such as `60s`, as Jupiter's
`junit.jupiter.execution.timeout.default` is. This repository's build sets both
to 60 seconds.

<!-- testing-junit-aspects -->
```kotlin
import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.alone
import dev.surefold.junit.expect
import dev.surefold.junit.flaky
import dev.surefold.junit.ignore
import dev.surefold.junit.nonFlaky
import dev.surefold.junit.tag
import dev.surefold.junit.timeout
import dev.surefold.resource
import kotlin.time.Duration.Companion.seconds

// Stands in for a connection to the service under test.
class Wire {
    fun roundTrip(frame: String): String = frame

    fun reconnects(): Boolean = true
}

// Every test is tagged "io", and each runs within 30 seconds, retries and all.
object WireSuite : SharedSuite<Wire>(aspects = tag("io") + timeout(30.seconds)) {
    override val shared: Resource<Wire> = resource { Wire() }

    init {
        test("frames survive a slow reader", nonFlaky(50) + alone) { wire ->
            expect(wire.roundTrip("frame") == "frame")
        }
        test("reconnects after a reset", flaky(3) + timeout(5.seconds)) { wire -> expect(wire.reconnects()) }
        test("speaks HTTP/3", ignore("no QUIC on CI")) { _ -> expect(false) }
    }
}
```

`tasks.test { useJUnitPlatform { includeTags("io") } }` then runs only the
tagged tests, Jupiter's `@Tag`s among them.

### Testing an aspect

An aspect's test runs a suite through JUnit's `EngineTestKit` and checks how
each of its tests ended. `surefold-junit-testkit` says that in one call:
`shouldHaveSucceeded`, `shouldHaveFailed` and `shouldHaveSkipped` pass if
exactly the tests named ended that way, in any order. A miss shows how each of
the others ended, with a failed test's message. `failureOf(test)` answers what a
test threw, `failures()` what each failed test threw by name, and `names()` the
tests in the order their events happened. `shouldHaveSkipped(mapOf(test to
reason))` also checks why each test was skipped, `skipReasons()` answers the
reasons by name, and `tags()` each test's tags by name. `logs()` answers a failed
test's log lines by name, one string per line as the report shows them, and
`reported(key)` what each test reported under a key, such as `flaky`'s
`attempts`. `finishOrder()` answers the names of the tests and suites that
finished, in the order they finished, so a test can check that a suite finished
after its tests.

```kotlin
val events = EngineTestKit.engine("surefold").selectors(selectClass(RetriedSuite::class.java)).execute().testEvents()

events shouldHaveSucceeded listOf("passes on its third attempt")
events.failureOf("never passes").message shouldContain "attempt 3 of 3"
```

## Applications

`testApp(module) { root: Root -> … }` starts a graph, runs the block with its
root, and releases the graph however the block ends. `subgraph<Root>()` keeps
only the nodes the root is reached through, and `overriding` replaces a node,
refusing a key the graph does not already have so that a typo cannot leave the
real node running. See [Testing an application](app.md#testing) and the
[cookbook](cookbook.md#test-the-wiring-not-one-class). A test of one class
needs none of this: it calls the class's constructor.

## Streams

`surefold-stream-test` provides `TestStreams`, a backend that runs every stage on
the calling thread in a fixed order, with `tick`, `groupedWithin`,
`restartOnDefect` and `mapPar` waiting only on the test's `TestClock`. `start`
returns once the run is over or waiting on a later time, and `emitted()` reads
what a collecting run has produced so far:

```kotlin
val clock = TestClock()
val running = Stream.tick(1.minutes, "t").runCollect().start(TestStreams(clock))

clock.adjust(1.hours)
running.emitted().size shouldBe 60
```

See [Time a test owns](stream.md#time-a-test-owns).

## Actors

`behaviour.test()` runs one actor on the calling thread, and
`testActors { }` runs several that message each other. A `send` returns once
that message and everything it caused has been handled, restarts record their
delays instead of waiting, and `advance` moves a test clock and delivers due
timers. `testActors` can also reorder deliveries from a seed and inject lost,
duplicated, delayed and crashed messages. See
[Testing actors](actors.md#testing), whose examples the build runs.

## Surefold's own documentation tests

The guides' examples are compiled against the library by tests that read the
Markdown files: `CoreGuidesSuite`, `StreamGuidesSuite`, `CookbookSuite`,
`ActorsGuideTest` and `GuideExampleTest`. A fence marked with an HTML comment
such as `<!-- actors-actor -->` is extracted, compiled, and in some cases run,
so a stale example fails the build.
