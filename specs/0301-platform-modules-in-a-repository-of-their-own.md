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

- **Moving sagas, actors, streams, the cluster, the app modules or the observability integrations.** They stay.
  Sagas (0298–0300) are built here.
- **Moving the Kafka, SNS and SQS integrations** in this spec (open question 1).
- **Renaming anything.** Artifacts keep `dev.surefold:<same name>`, and packages keep `dev.surefold.*`.
- **Changing behaviour.** This is a move. No module's API changes in it.

## Shape

A new repository, `surefold/surefold-platform` (open question 2), holding:

- **All 19 `data/` modules**, at the same paths in the same `data/` folder, with their git history (filtered
  with `git filter-repo`, so `git log` and `blame` still work).
- **Their guides:** `auth.md`, `files.md`, `http-stores.md`, `jobs.md`, `kms-operations.md`, `postgres-notify.md`,
  `tenancy.md`, `tenancy-crypto.md`, and the Postgres section of `flags.md`, with their compiled-fence tests.
- **`bench/surefold-jobs-load`**, and the `surefold-app-flyway` and `surefold-app-pekko` tests that migrate or read
  `data/` modules.
- **The same rules:** `AGENTS.md`, `CODING_STANDARDS.md`, specs first, `NoOtherDependenciesTest`, Kover at 90%,
  detekt and spotless, and copies of the workflows.

It depends on Surefold's published artifacts, never on a source checkout:

```kotlin
// surefold-platform/data/surefold-jobs/build.gradle.kts
dependencies {
    api("dev.surefold:surefold:0.19.0")
    implementation("dev.surefold:surefold-stream-forks:0.19.0")
    testImplementation(testFixtures("dev.surefold:surefold-actor-journal-jdbc:0.19.0"))
}
```

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

- [ ] **`spec-0301-fixtures`** (here) — publish `surefold-actor-journal-jdbc`'s test fixtures and release.
      Done when: a project outside this build resolves `testFixtures("dev.surefold:surefold-actor-journal-jdbc")`.
- [ ] **`spec-0301-seed`** (new repository) — the filtered history, the build on published 0.19.0, gates and CI.
      Done when: `./gradlew build` there is green against Maven Central artifacts only.
- [ ] **`spec-0301-release`** (new repository) — the release and snapshot workflows publishing the same coordinates.
      Done when: a snapshot of every moved artifact is published from there.
- [ ] **`spec-0301-remove`** (here) — delete `data/`, `surefold-jobs-load` and the moved tests, and add the pointers.
      Done when: `./gradlew build` is green here, and no `settings.gradle.kts` line names a moved module.

## Acceptance

```bash
./gradlew build                                   # in both repositories
grep -c 'module("data"' settings.gradle.kts       # 0 here
```

## Open questions

1. **Move Kafka, SNS and SQS too?** Recommended: not in this spec. They sit on `surefold-stream` and Pekko, which
   stay, and moving them is a separate call to make once this one has settled.
2. **What is the new repository called?** Recommended: `surefold/surefold-platform`. Creating it is yours to do;
   no agent can.
3. **Versions: in lockstep with Surefold, or their own?** Recommended: their own, starting at the version that
   moves (0.19.0), each release naming the Surefold version it was built against.
4. **Does the last Surefold release that still contains `data/` get a notice in its README?** Recommended: yes,
   one line under Status saying where the modules went and that coordinates are unchanged.
