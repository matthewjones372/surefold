# 0302 — Integrations in a repository of their own

## Problem

The rule this repository is meant to keep is that Surefold's core depends on the JDK and Kotlin and nothing else.
It keeps it per module, but the repository as a whole carries about 35 modules that each wrap an outside library:
Pekko, Kafka, the AWS SDK, protobuf, Avro, Typesafe Config, Liquibase, Flyway, OpenFeature, SLF4J, Micrometer,
OpenTelemetry and Arrow. Their upgrades, CVEs and breaking releases land in the same build, review and release as
the concurrency core, so every Pekko bump ships a new Surefold. 0301 moves the platform modules out. Keeping the
integrations here, or putting them in the platform repository, recreates the same mix one level down.

## Not doing

- **Moving the platform modules.** 0301 does that.
- **Splitting integrations further,** one repository per library. They share a release and a reviewer.
- **Renaming anything.** Artifacts keep `dev.surefold:<same name>`, and packages keep `dev.surefold.*`.
- **Changing behaviour.** This is a move.

## Shape

Three repositories, each depending only on the ones before it, through published artifacts:

```
surefold/surefold-core          JDK + Kotlin only
        ▲
surefold/surefold-integrations  core + one outside library per module
        ▲
surefold/surefold-platform      core + integrations (0301)
```

**Moves to `surefold-integrations`**, with its history, guides and fence tests:

| Family | Modules |
|---|---|
| Pekko | `surefold-pekko`, `-actor-pekko`, `-stream-pekko`, `-app-pekko`, and `-stream-parity` (it runs every backend) |
| Messaging | `surefold-kafka`, `-kafka-pekko`, `-kafka-test`, `-sqs`, `-sqs-pekko`, `-sqs-test`, `-sns`, `-sns-test` |
| Wire formats | `surefold-actor-remote-protobuf`, `-avro`, `-kotlinx` |
| Discovery | `surefold-cluster-kubernetes`, `-cluster-aws` |
| App | `surefold-app-typesafe`, `-app-cluster`, `-app-kotlinx`, `-app-liquibase`, `-app-openfeature`, `-app-openfeature-flagd` |
| Observability | `surefold-slf4j`, `-micrometer`, `-otel`, `-otel-test`, `-jdbc-otel` |
| Other | `surefold-openfeature`, `-arrow` |
| Benchmarks | `surefold-kafka-benchmarks`, `-jdbc-otel-bench`, `-actor-benchmarks`, `-stream-benchmarks` (they run Pekko) |

**Stays in `surefold-core`**: `surefold`, `-replay`, `-cache`, `-structured`, `-app`, `-app-actor`, `-stream`,
`-stream-forks`, `-stream-actors`, `-stream-test`, `-actor`, `-actor-journal-jdbc`, `-actor-projection`,
`-actor-remote`, `-cluster`, `-test`, the test and build tooling (open question 1) and `surefold-core-benchmarks`.
`surefold-bank` moves to `surefold-platform` with 0301 (question 2), and so does `surefold-app-flyway` (maintainer,
2026-11-04): its main code names the platform modules' Flyway schemas, and its suites need their jars.

- **This repository is renamed `surefold-core`** once the moves are done. GitHub redirects the old URL; badges,
  `llms.txt` and links are updated in the same step.
- **The core's guides keep pointers.** `docs/modules.md` lists every moved module with a link, and `docs/arrow.md`,
  `observability.md`, `kafka.md`, `sqs.md`, `sns.md` and the Pekko sections move with their modules.
- **One build when you ask for it.** With the repositories checked out side by side, a repository's
  `settings.gradle.kts` includes the ones it depends on, and Gradle substitutes their source for the published
  artifacts by group and name. CI never checks them out, so it always builds against the pinned snapshots on GitHub Packages (0301
  question 5):

  ```kotlin
  // surefold-integrations/settings.gradle.kts (and surefold-platform's, with both upstreams)
  listOf("../surefold-core").filter { file(it).exists() }.forEach { includeBuild(it) }
  ```
- **Each repository has its own version** (0301 question 3), released to Central only once all three are proven. An integrations release says
  which core version it was built against, and a platform release names both.

## Why this shape

One direction of dependency per repository means a core release can never be broken by a library's upgrade, and an
integration's upgrade never needs a core release. **Alternative:** integrations inside `surefold-platform`, two
repositories. Fewer to release, but a Pekko or AWS bump would again ship with unrelated platform features.
Recommended (maintainer, 2026-11-04): a third repository.

## Stack

- [ ] **`spec-0302-seed`** (new repository) — the filtered history, the build on a pinned core snapshot, gates, CI,
      and the side-by-side composite build with its layout in `AGENTS.md`.
      Done when: `./gradlew build` there is green against GitHub Packages snapshots only, and with `surefold-core`
      checked out beside it a change to core's source reaches the integrations' tests without publishing.
- [ ] **`spec-0302-release`** (new repository) — the snapshot workflow, and a release workflow kept ready but
      unused until the first release.
      Done when: a snapshot of every moved artifact is published from there to GitHub Packages.
- [ ] **`spec-0302-guides`** (here) — a home here for the suites that compile core's own guides, which live in
      moving modules today: `ReadmeExampleSuite` (stream-pekko), `CoreGuideTest`'s core pages and `CookbookTest`
      (app-pekko), `ReportedSuite` (app-typesafe) and `GuideConfigSuite` (app-cluster). A fence that needs an
      integration moves to that integration's guide in `surefold-integrations`, or is rewritten without it.
      Done when: `README.md`, `stream.md`, `core.md`, `testing.md`, `flags.md`, `cookbook.md` and `cluster.md` are
      compiled by suites in modules that stay here, and those suites name no moved module.
- [ ] **`spec-0302-wiring`** (`surefold-integrations`) — `surefold-app-gradle`'s TestKit tests of apps on
      `app-pekko`, `-typesafe`, `-kotlinx`, `-openfeature` and `-openfeature-flagd`, run there against core's
      published plugin (maintainer, 2026-11-04).
      Done when: those tests pass there, and `surefold-app-gradle`'s build here names no moved module.
- [ ] **`spec-0302-remove`** (here) — delete the moved modules, keep the pointers.
      Done when: `./gradlew build` is green here and every remaining module's runtime classpath is JDK and Kotlin
      only, apart from the tooling question 1 keeps.
- [ ] **`spec-0302-rename`** (here) — the repository renamed `surefold-core`, and every link and badge updated.
      Done when: no file here links to `github.com/surefold/surefold/` except through the redirect notice.

## Acceptance

```bash
./gradlew build                     # in all three repositories
```

## Open questions

1. **Test and build tooling** (`surefold-junit`, `-junit-testkit`, `-assert`, `-compiler`, `-app-compiler`,
   `-app-gradle`, JMH benchmarks) depends on JUnit, opentest4j, the Kotlin compiler, Gradle or JMH. Keep it in core?
   Answered (maintainer, 2026-11-04): yes, as the one exception. Every core test runs on it, so moving it would
   make core depend on another repository to test itself. The rule reads: runtime modules are JDK and Kotlin only;
   test and build tooling is exempt, and never reaches a service's runtime classpath.
2. **`surefold-bank`** uses `surefold-app-liquibase` and Postgres. Answered (maintainer, 2026-11-04): it moves to
   `surefold-platform` (0301), keeping `app-liquibase`. Core proves its sagas with its own tests (0299).
3. **Order of 0301 and 0302.** Answered (maintainer, 2026-11-04): seed `surefold-integrations` (0302) from a snapshot of
   this repository, seed `surefold-platform` (0301), remove the moved modules here, then rename this repository
   `surefold-core`. No release first (0301 question 5). Sagas (0298–0300) are built here meanwhile.
4. **A composite build across the repositories?** Answered (maintainer, 2026-11-04): yes, opt-in by checking the
   repositories out side by side; CI stays on pinned snapshots.
