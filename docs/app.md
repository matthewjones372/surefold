# Applications

`surefold-app` describes an application as a value: a dependency graph you can
check, draw, subset, override and start. Each node says what it builds and
takes what it needs as parameters, so the whole graph is known before any of it
is constructed.

With the graph as a value, Surefold can:

- report every missing dependency and cycle before anything starts, at build
  time through a Gradle plugin and in the editor through a compiler plugin;
- start independent nodes in parallel, wait for readiness probes, and release
  everything in reverse dependency order on shutdown;
- start only the part of the graph a test needs, with fakes swapped in safely.

There are no annotations, no reflection-driven container and no effect type.
A node is a function, and a module is an ordinary Kotlin expression.

```kotlin
dependencies {
    // brings surefold, and through it nothing else
    implementation("io.github.matthewjones372:surefold-app:0.17.0")
}
```

Everything here is in `dev.surefold.app`. This page explains
how the graph behaves. The [cookbook](cookbook.md) has one compiled recipe per
task, including configuration, migrations and actors.

1. [Nodes and modules](#nodes-and-modules)
2. [Checking a graph](#checking-a-graph)
3. [Starting and stopping](#starting-and-stopping)
4. [Readiness and health](#readiness-and-health)
5. [Testing](#testing)
6. [Integrations](#integrations)
7. [Checking the graph in the build](#checking-the-graph-in-the-build)
8. [Checking the graph in the editor](#checking-the-graph-in-the-editor)
9. [Limitations](#limitations)

## Nodes and modules

```kotlin
import dev.surefold.app.Module
import dev.surefold.app.boundTo
import dev.surefold.app.closingOf
import dev.surefold.app.single
import dev.surefold.app.singleOf

val persistence: Module =
    closingOf { cfg: DbConfig -> HikariDataSource(cfg) }.boundTo<DataSource>() +
        singleOf(::PgUserRepo).boundTo<UserRepo>() +
        single<OrderRepo, DataSource> { ds -> PgOrderRepo(ds) }

val domain: Module =
    singleOf(::RegisterUser) +
        single { users: UserRepo, orders: OrderRepo -> PlaceOrder(users, orders) }

val app: Module = config + persistence + domain + web
```

- **A node is keyed by the type it provides**, and is built once however many
  nodes depend on it: one `DataSource`, shared by both repositories.
- **Its parameters are its dependencies.** A node can take up to nine; past
  that, group them into a node of their own.
- **`singleOf(::Constructor)`** reads the key and the dependencies off a
  constructor reference. `single { … }` takes a lambda for anything else.
- **`boundTo<Interface>()`** keys a node as the type its dependents ask for.
  Without it, `singleOf(::PgUserRepo)` is keyed `PgUserRepo`.
- **`closingOf`** builds an `AutoCloseable` and closes it on release.
  `singleOf(::Server, Server::stop)` gives a release with another name, and
  `install(acquire) { held, exit -> … }` inside `single` gives one that sees
  how the application ended.
- **`plus` combines modules.** Order means nothing, because matching is by
  type, except where two modules provide the same key: then the right-hand
  node wins.

Two values of one type need two keys. A `@JvmInline value class Replica(val ds: DataSource)`
costs nothing at runtime and says which one a dependent wants.

A Kotlin quirk shapes the API: there is no partial type-argument inference, so
`single<UserRepo, DataSource> { ds -> … }` has to name the dependency as well
as the key. `boundTo` avoids that by taking the key afterwards. A Java factory
returns a platform type such as `Tracer!`, which matches no node asking for
`Tracer`; name the key with `boundTo` there too.

## Checking a graph

```kotlin
import dev.surefold.app.findings
import dev.surefold.app.render
import dev.surefold.app.report
import dev.surefold.app.validate

app.validate()                                    // Either<NonEmptyList<WiringError>, Plan>
app.findings(root = typeOf<HttpServer>()).report() // every error and warning, as text
app.render()                                      // the graph as Mermaid
```

`validate` runs no node. It answers with every missing key at once, because a
graph is usually short of a whole module rather than one node. If nothing is
missing it checks for cycles, and reports a cycle as the path around it.

Each node records the file and line it was written on, so a report names the
recipe to edit:

```
surefold-app wiring

❯ error: missing DataSource
❯     for OrderRepo          Wiring.kt:42

❯ warning: Tracer provided twice
❯     Telemetry.kt:14        shadowed
❯     Local.kt:9             wins

❯ warning: nothing reaches KafkaProducer    Kafka.kt:9
```

`findings` reports four kinds of fault:

| Fault | Severity |
|---|---|
| a key nothing provides | error |
| a cycle | error |
| a key provided twice, where `plus` shadowed one | warning (not reported for `overriding`, which replaces on purpose) |
| a node the root does not reach | warning, naming only the top of each unreached subtree |

Omitting `root` skips the reachability check. `render` draws the graph in a
stable order, so the drawing can be kept in a golden file and reviewed. The
[cookbook](cookbook.md#check-the-graph-without-running-it) shows a rendered
example.

## Starting and stopping

An application declares its graph and the node it starts from:

```kotlin
import dev.surefold.app.Module
import dev.surefold.app.SurefoldApp
import dev.surefold.app.runApp
import kotlin.system.exitProcess

object Petshop : SurefoldApp<HttpServer>() {
    override val module: Module = config + persistence + domain + web
}

fun main(): Unit = exitProcess(runApp(Petshop).code)
```

Starting runs the graph one topological layer at a time. Nodes in the same
layer start in parallel on [Surefold forks](core.md#forking), so the first refusal
or failure interrupts its siblings, and everything already acquired is
released before `runApp` returns.

Releases run in reverse **topological** order, not reverse acquisition order:
with parallel starts, acquisition order is whichever fork finished first,
which is no order to release in.

- **`run`.** A service writes no `run`: the default calls `awaitShutdown()`,
  which parks until the process is asked to stop, and the graph's release then
  stops the server. A job overrides `AppScope.run(root)` with its work, and the
  graph is released when it returns.
- **`refuse(reason)`.** A node that cannot start says why, and the start fails
  with that reason rather than a stack trace:
  `single { sys: Sys -> DbConfig(sys.env("DB_URL") ?: refuse("DB_URL is not set")) }`.
- **Shutdown.** `runApp` installs a JVM shutdown hook that requests shutdown
  and waits up to 30 seconds for the releases to finish. A test can request it
  with `Shutdown.request()` instead of sending a signal.
- **Configuration.** Each `config` section logs the settings it read, where
  each came from and what it overrode, as it is read, refusal included; nothing
  in `main` asks for it. See the
  [cookbook](cookbook.md#where-a-setting-came-from).
- **Exit code.** `runApp` returns an `ExitCode` and prints what went wrong to
  stderr; it never ends the process itself. The `exitProcess` is yours, and
  worth calling: a stray non-daemon thread would otherwise keep the JVM alive.

The root is a type argument of `SurefoldApp` so that a build tool can read it from
the class file without running `main`. `runApp(module) { root: HttpServer -> … }`
is the same without a `SurefoldApp`, for tests and one-file programs.

### Reading the environment

`Sys` is the process environment as a node: `sys.env(name)` and
`sys.property(name)` answer `String?`. Provide `single<Sys> { RealSys }` in the
application and `FakeSys(mapOf("DB_URL" to "jdbc:…"))` in a test, so no test
has to set a JVM-wide environment variable. For configuration files, use
[`surefold-app-typesafe`](cookbook.md#configuration-a-module-owns), or
`surefold-app-kotlinx` to read a section from its `@Serializable` class.

## Readiness and health

A node that is constructed is not necessarily ready: a pool with no
connection, or a consumer with no partition assignment. A probe makes "started"
mean "ready":

```kotlin
import dev.surefold.app.closingOf
import dev.surefold.app.probe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

val kafka: Module =
    closingOf { cfg: KafkaConfig -> KafkaClient.connect(cfg) }
        .probe("kafka", timeout = 2.seconds, attempts = 10, interval = 500.milliseconds) { it.assigned() }
```

- Dependents wait for the probe, not just the constructor.
- Each attempt runs inside its own timeout, so a probe that hangs does not
  hang the start. The wait between attempts uses the inherited
  [clock](core.md#what-a-fork-inherits).
- A probe that throws has failed, like one that answers `false`, and is asked
  again while it has attempts left. A start that gives up reports what the
  last attempt threw.
- `asked = Asked.WhenStarted` defers a probe until the whole graph is built,
  for a check that needs other nodes running first.

The same probes answer afterwards through `HealthRegistry`, a node the running
application provides itself, so an HTTP route can depend on it:

```kotlin
single { health: HealthRegistry -> Routes(ready = { health.readiness() }, live = { health.liveness() }) }
```

`readiness()` asks every probe at once, each inside its own timeout, and
answers `Up`, `Degraded` (only probes with `critical = false` failing) or
`Down`. A probe that throws is listed as failing; `readiness()` itself never
throws. `liveness()` answers `Down` with the reason `starting` until the graph
is running.

## Testing

A test of one class calls its constructor; a test whose claim spans wiring
uses the graph.

```kotlin
import dev.surefold.app.overriding
import dev.surefold.app.subgraph
import dev.surefold.app.testApp

// One class, its collaborators as arguments.
RegisterUser(InMemoryUsers()).register("ada") shouldBe Registered("ada")

// A claim about the wiring: the real nodes, one faked, released however the block ends.
testApp(app.subgraph<Checkout>().overriding(single<Payments> { DecliningPayments() })) { checkout: Checkout ->
    checkout.order(basket) shouldBe Declined
}
```

The constructor is the narrowest seam a class has, and a test through it
breaks only when the class does. The graph is for claims that hold only with
the nodes together: start and release order, readiness, a use case across an
actor, a pool and a migration, a fake replacing one node among real ones.
The [cookbook](cookbook.md#test-the-wiring-not-one-class) has both, compiled.

- **`subgraph<Root>()`** keeps only the nodes the root is reached through, so
  a test of the wiring starts a few nodes rather than a broker and a server.
- **`overriding`** replaces nodes, and refuses a key the graph does not already
  hold. Plain `plus` would add the fake beside the real node and leave the real
  one running: a green test that faked nothing.
- **`testApp`** starts the graph, runs the block with the root, and releases
  the graph however the block ends, a failed assertion included.

Nothing in a graph is a global singleton and there is no service locator,
which is what makes all three possible. `app.validate().shouldBeRight()` is a
one-line test that the whole application wires, although the Gradle plugin
below runs that check without one.

## Integrations

Each integration is a separate module that makes something a node:

| Module | Provides | Recipe |
|---|---|---|
| `surefold-app-typesafe` | `config<T>("section") { … }`, a HOCON section read into a type, with every fault in a bad file reported at once; `secret(path)`; where each setting came from | [Configuration](cookbook.md#configuration-a-module-owns) |
| `surefold-app-kotlinx` | `config<T>("section")`, the section read from `T`'s own `@Serializable` properties, every fault at once | [Configuration](cookbook.md#configuration-a-module-owns) |
| `surefold-app-liquibase` | `migrations("db/changelog.xml")`, a node providing `surefold-app`'s `Migrated`, so readers of the database start after migrations | [Migrations](cookbook.md#run-migrations-as-a-step) |
| `surefold-app-flyway` | `flyway(service = "classpath:db/migration")`, the same `Migrated` from Flyway's SQL scripts, with `clean` disabled and validation on | [Flyway](flyway.md) |
| `surefold-app-openfeature` | `openFeature { provider }`, an OpenFeature provider set before anything reads a flag, keyed by `Client`, ready while the provider is, and shut down on release | [Feature flags](flags.md) |
| `surefold-app-pekko` | `actor<T>("name") { … }`, a Pekko typed actor keyed by `ActorRef<T>`, stopped with `gracefulStop` | [Pekko actor](cookbook.md#spawn-a-pekko-actor) |
| `surefold-app-actor` | `actors()`, a `surefold-actor` scope that lives as long as the application, and `actor("name") { … }`, keyed by Surefold's `ActorRef<M>` | [Surefold actor](cookbook.md#spawn-a-surefold-actor) |
| `surefold-app-cluster` | `cluster("path")`, a cluster membership joined as a HOCON section describes | [Joined from config](cluster.md#joined-from-config) |

A choice between modules can also be made inside the graph:
`choice<Settings, Provided> { settings -> if (…) moduleA else moduleB }` is a
node that depends on a setting and expands into the chosen module once the
setting is built, so the other branch is never built. See the [cookbook](cookbook.md#a-setting-that-picks-a-module).

## Checking the graph in the build

`surefold-app-gradle` is a Gradle plugin that checks every `SurefoldApp` in a project
as it compiles. It is published to Maven Central, not the Gradle Plugin Portal,
so the plugin repositories must include Maven Central:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.4.10"
    id("dev.surefold.wiring") version "0.17.0"
}

repositories { mavenCentral() }

surefoldWiring {
    failOn = "WARN"                                  // "FAIL" (the default) or "WARN"
    diagrams = layout.buildDirectory.dir("wiring")   // default build/reports/surefold
}
```

The plugin registers a `surefoldWiring` task once the Kotlin JVM plugin is
applied. The task finalizes `classes` and is a dependency of `check`, so it
runs whenever the IDE or a run configuration builds the project. It:

- finds every `SurefoldApp` in the project's own class output, loading classes
  without initialising them, so unrelated static state is not run;
- initialises each match, which runs the expression that assembles its graph
  but none of the nodes in it;
- checks the graph against the root it declares, and writes `<Name>.mmd`, a
  Mermaid diagram, for each.

A project with no `SurefoldApp` passes, so a library module in an application
build needs no opt-out. Faults are printed in the compiler's format, which the
IDE turns into entries in its Build window that open the node's source:

```
e: file:///…/petshop/app/src/main/kotlin/petshop/app/Wiring.kt:92:1 surefold-app: PetShop needs ActorRef<Shop>, and nothing builds it
```

Because `single` is inline, the JVM reports a synthetic line for a node; Surefold
reads the class file's SMAP to map it back to the line the node was written on.

The task runs the graph-building expression, so it sees everything: nodes
built in conditionals, actor nodes, modules from other Gradle modules. It is
the authoritative check.

## Checking the graph in the editor

Applying the Gradle plugin also applies `surefold-app-compiler`, a K2 compiler
checker that reports the same faults while you type, with no build:

![IntelliJ underlining `singleOf(::ActorPetShop)` in red, with the tooltip
"surefold-app: PetShop needs ActorRef<Shop>, and nothing builds it".](editor-error.png)

It reads the source rather than running it, so it follows only the shapes it
knows:

- `single`, `singleOf`, `closingOf` and `choice`;
- `surefold-app-typesafe`'s `config` and its `loadedConfig`, `configOf`,
  `configFromResource` and `configFromFile`; `surefold-app-kotlinx`'s `config`;
  `surefold-app-liquibase`'s `migrations`; `surefold-app-flyway`'s `flyway`; `surefold-app-openfeature`'s `openFeature`; `surefold-app-openfeature-flagd`'s `flagdFlags`;
  `surefold-app-pekko`'s `actor`;
  `surefold-app-actor`'s `actors` and `actor`;
- `plus`, `overriding`, `overridingConfig`, `boundTo` and `probe`;
- references to `val`s and single-expression functions in the same
  compilation, `if` and `when` with single-expression branches, and `as`.

When it meets anything else, including `surefold-app-cluster`'s `cluster` and a
module defined in another Gradle module, it does not guess: it reports the
warning `SUREFOLD_APP_UNREAD` and leaves that application to the `surefoldWiring` task.
A false error on working code would be worse than a fault reported a moment
later. It also reports
`SUREFOLD_APP_UNREAD` when the Kotlin compiler's version differs from the one the
checker was built for.

IntelliJ runs third-party compiler plugins in the editor only after
`kotlin.k2.only.bundled.compiler.plugins.enabled` is unchecked in its registry
(Help, Find Action, Registry).

### What the compiler plugin warns about

The same plugin warns where code hand-rolls something Surefold provides:

| Warning | On | Use instead |
|---|---|---|
| `SUREFOLD_RUN_NEVER_RETURNS_ON_SHUTDOWN` | a `SurefoldApp`'s `run` that calls a zero-argument `block()`, `join()` or `await()` and never `awaitShutdown()` | `awaitShutdown()`, so a SIGTERM releases the graph |
| `SUREFOLD_SCHEDULER_BY_HAND` | `Executors.newScheduledThreadPool`, `newSingleThreadScheduledExecutor`, `ScheduledThreadPoolExecutor` or `java.util.Timer` in main code | `Stream.tick(every)` started on a [stream backend](stream.md#backends) |
| `SUREFOLD_SECRET_AS_STRING` | `string("…")` in a `config` reading, where the path ends in a secret-like name such as `password`, `token` or `apiKey`, in main code | `secret(path)` |
| `SUREFOLD_SLEEP_IN_TEST` | `Thread.sleep` or `TimeUnit.sleep` in test code | `eventually(within)` from `surefold-test`, or a [test clock](testing.md#time) |

Each is a warning, silenced where it is intended by name, for example
`@Suppress("SUREFOLD_SLEEP_IN_TEST")`.

### What the compiler plugin refuses inside a step

A [`transact`](core.md#values-changed-together) step can
run more than once, so anything in it besides changing `Shared` values happens
once per run, not once per commit. Inside a step (a `transact { }` block, a
`firstOf` branch, or a function taking `Transaction` as its receiver) the
plugin reports `SUREFOLD_EFFECT_IN_TRANSACT`, an **error**, on:

| On | Do instead |
|---|---|
| Any call into `java.io`, `java.net`, `java.nio.channels`, `java.sql`, `kotlin.io` or `kotlin.io.path` (`File.readText`, `readln`, …) | Do it before or after `transact` |
| `Thread.sleep`, `TimeUnit.sleep`, `Future.get`, `CompletableFuture.get` or `join`, `Deferred.await`, `Lock.lock`, `synchronized` | Wait with `waitForChange()`, or before or after `transact` |
| `scope`, `async`, `parZip`, `parMap`, `raceN` | Fork before or after `transact` |
| `logDebug`/`logInfo`/`logWarn`/`logError`, `Logger.log`, `Counter.increment`, `Gauge.set`, `Histogram.record`, `Hub.publish`, `print`, `println` | [`afterCommit { }`](core.md#effects-once-the-step-commits), or after `transact` |
| `ActorRef.tell` | `tellAfterCommit(ref, message)` from `surefold-actor` |
| A write to a `var` the step did not declare: a local of the enclosing function, a property of the enclosing object, or a top-level one | Keep the value in a `Shared` |
| `Shared.peek()` | `get()` |

A write to a property of some other object the step was handed is not judged,
because the plugin cannot see where that object came from. Calls into
functions from other modules are trusted. One finding is silenced with
`@Suppress("SUREFOLD_EFFECT_IN_TRANSACT")`.

## Limitations

- **One instance per key.** There are no scopes or named instances; use a
  wrapper type for a second instance.
- **No annotation processing.** A module is an expression, not a declaration,
  so annotation processors such as KSP have nothing to read. The Gradle task
  is the complete check, and the editor check covers the common shapes.
- **No effect type.** A node is a function, and `suspend` appears nowhere.
