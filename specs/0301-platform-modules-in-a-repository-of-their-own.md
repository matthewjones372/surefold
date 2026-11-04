# 0301 — Platform modules in a repository of their own

## Problem

Surefold is 81 modules: about 51,000 lines of main code and 69,000 of tests. A change to the core runs a build,
review and release cycle that also carries 19 `data/` modules: auth, OIDC, tenancy, tenancy crypto, files, jobs,
caches on Postgres and Redis, idempotency, rate limits, flags on Postgres and Postgres notify. They are application
platform features, not concurrency, streams or actors. Each of them uses only the public API of the modules it
builds on, and nothing outside `data/` uses one of them except in tests and one benchmark. Keeping them here makes
Surefold read as a framework, slows every PR's affected-module build, and puts every `data/` change through the
same release as the core.

## Not doing

- **Moving sagas, actors, streams or the cluster.** They stay in core. Sagas (0298–0300) are built here.
- **Moving the integrations.** 0302 moves every module that wraps an outside library to `surefold-integrations`.
- **Renaming anything.** Artifacts keep `dev.surefold:<same name>`, and packages keep `dev.surefold.*`.
- **Changing behaviour.** This is a move. No module's API changes in it.

## Shape

A new repository, `surefold/surefold-platform` (open question 2), holding:

- **All 19 `data/` modules**, at the same paths in the same `data/` folder, with their git history (filtered
  with `git filter-repo`, so `git log` and `blame` still work).
- **Their guides:** `auth.md`, `files.md`, `http-stores.md`, `jobs.md`, `kms-operations.md`, `postgres-notify.md`,
  `tenancy.md`, `tenancy-crypto.md`, `flyway.md`, and the Postgres section of `flags.md`, with their compiled-fence
  tests.
- **`examples/surefold-bank`** (0302 question 2), the example built on core, integrations and platform alike.
- **`bench/surefold-jobs-load`**, **`app/surefold-app-flyway`** (moved here from 0302's table, maintainer, 2026-11-04:
  it names these modules' Flyway schemas), and the `surefold-app-pekko` test that reads `data/` modules.
- **The same rules:** `AGENTS.md`, `CODING_STANDARDS.md`, specs first, `NoOtherDependenciesTest`, Kover at 90%,
  detekt and spotless, and copies of the workflows.

It depends on the published artifacts of `surefold-core` and `surefold-integrations` (0302), never on a source
checkout; `surefold-jobs-otel` and `surefold-openfeature-postgres`, for instance, need `surefold-otel` and
`surefold-openfeature`:

```kotlin
// surefold-platform/data/surefold-jobs/build.gradle.kts
dependencies {
    api("dev.surefold:surefold:$surefoldCore")        // a pinned snapshot, e.g. 0.19.0-SNAPSHOT
    implementation("dev.surefold:surefold-stream-forks:$surefoldCore")
    testImplementation(testFixtures("dev.surefold:surefold-actor-journal-jdbc:$surefoldCore"))
}
```

- **Upstream comes from GitHub Packages, not Central, until the first release** (question 5): each repository
  pins the snapshot versions it builds on in `gradle.properties` and reads them from
  `maven.pkg.github.com/surefold/<repository>`, which `snapshot.yml` (0281) fills on every push to main.
- **The same side-by-side composite build as 0302**: `settings.gradle.kts` includes `../surefold-core` and
  `../surefold-integrations` when they are checked out there, so one build spans all three; CI never does.
- **Surefold publishes `surefold-actor-journal-jdbc`'s test fixtures** (its Postgres for tests), which eight
  `data/` modules use today through `testFixtures(project(...))`.
- **Surefold keeps a pointer.** `docs/modules.md`'s Data section becomes a short table linking each module to the
  new repository, and so does the README. The guides above are replaced by one-line links.
- **Specs stay.** The `data/` specs (0216–0220 for jobs, 0225 and 0235–0237 for tenancy, and the rest) remain
  here as the record. The new repository
  starts its own numbering at 0001.

## Why this shape

The cut follows the dependency graph: `data/` modules depend on Surefold, and Surefold depends on none of them,
so it is the one split with no cycle. **Alternative:** one repository with two release trains. That keeps one
checkout but none of the smaller build or reading. **Alternative:** split by module, a repository each. That's
19 repositories for one team. Recommended: one platform repository.

## Stack

- [x] **`spec-0301-fixtures`** ([#1077](https://github.com/surefold/surefold-core/pull/1077)) (here) — publish
      `surefold-actor-journal-jdbc`'s test fixtures with its snapshot.
      Done when: a project outside this build resolves its `testFixtures(...)` from GitHub Packages.
- [x] **`spec-0301-seed`** ([surefold-platform#1](https://github.com/surefold/surefold-platform/pull/1)) (new
      repository) — the filtered history, the build on pinned upstream snapshots, gates, CI and the composite build.
      Done when: `./gradlew build` there is green against GitHub Packages snapshots only, and with both upstreams
      checked out beside it a change to their source reaches the platform's tests without publishing.
- [x] **`spec-0301-release`** ([surefold-platform#2](https://github.com/surefold/surefold-platform/pull/2)) (new
      repository) — the snapshot workflow, and a release workflow kept ready but unused until the first release
      (question 5).
      Done when: a snapshot of every moved artifact is published from there.
- [x] **`spec-0301-flags`** ([surefold-platform#3](https://github.com/surefold/surefold-platform/pull/3),
      [#1085](https://github.com/surefold/surefold-core/pull/1085)) (`surefold-platform`, then here) — the Postgres
      section of `flags.md`, flags per tenant on `surefold-openfeature-postgres`, moves to a platform guide with its
      compiled fence, and `CoreGuideTest` stops depending on `surefold-openfeature-postgres`. It comes before 0302's
      `spec-0302-guides-out`, which takes the rest of `flags.md` to `surefold-integrations`, since integrations cannot
      depend on platform.
      Done when: no guide here names a platform module, and the section compiles in `surefold-platform`.
- [x] **`spec-0301-remove`** ([#1095](https://github.com/surefold/surefold-core/pull/1095)) (here) — delete `data/`,
      `surefold-bank`, `surefold-jobs-load`, `surefold-app-flyway` and the moved tests, and add the pointers.
      Done when: `./gradlew build` is green here, and no `settings.gradle.kts` line names a moved module.

## Acceptance

```bash
./gradlew build                                   # in both repositories
grep -c 'module("data"' settings.gradle.kts       # 0 here
```

## Open questions

1. **Move Kafka, SNS and SQS too?** Answered (maintainer, 2026-11-04): yes, with every other integration, to a
   repository of their own, `surefold-integrations` (0302), not into this one.
2. **What is the new repository called?** Answered (maintainer, 2026-11-04): `surefold/surefold-platform`.
   Creating it is the maintainer's to do; no agent can.
3. **Versions: in lockstep with Surefold, or their own?** Answered (maintainer, 2026-11-04): each repository its own,
   each release naming the core and integrations versions it was built against. A
   BOM, if wanted, is a later spec.
4. **Does the last Surefold release that still contains `data/` get a notice in its README?** Answered (maintainer,
   2026-11-04): yes, one line under Status in the first release after the split (question 5), naming the three
   repositories and saying coordinates and packages are unchanged.
5. **Release before the split?** Answered (maintainer, 2026-11-04): no. Nobody depends on Surefold yet, so the
   repositories build against pinned snapshots on GitHub Packages, and the first Central release of each comes
   once all three are proven to work together.
