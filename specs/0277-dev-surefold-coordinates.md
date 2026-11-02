# 0277 — dev.surefold coordinates and packages

## Problem

surefold lives in the `surefold` GitHub organisation now, but it still publishes as `io.github.matthewjones372:<module>`.
Its Kotlin sources are in `io.github.matthewjones372.surefold.*`, and its Gradle plugins are
`io.github.matthewjones372.surefold.compiler` and `…surefold.wiring`. The `dev.surefold` namespace is verified on
Maven Central (DNS on surefold.dev). A user who reads `dev.surefold` in the README today finds nothing there, and
a personal namespace on an organisation's library suggests one maintainer's side project.

## Not doing

- Not renaming modules (`surefold-stream` stays `surefold-stream`) or changing any API beyond the package name.
- Not moving pelican, kimney or kotlin-golden-testing here. Each has its own change; pelican's follows this one,
  because it depends on `surefold-stream`.
- Not deleting or rewriting anything already on Central: old versions stay where they are.
- Not changing specs' text. Specs keep the names they were written with.

## Shape

```kotlin
// build.gradle.kts in a project using surefold
plugins { id("dev.surefold.wiring") version "0.18.0" }
dependencies { implementation("dev.surefold:surefold-stream:0.18.0") }

// sources
import dev.surefold.stream.Stream
```

Every `io.github.matthewjones372.surefold` package becomes `dev.surefold`, so `…surefold.stream` is `dev.surefold.stream`;
the source tree moves from `src/*/kotlin/io/github/matthewjones372/surefold/` to `src/*/kotlin/dev/surefold/`. The plugins
become `dev.surefold.compiler` and `dev.surefold.wiring`. Sibling libraries take a segment of their own:
`dev.surefold.pelican`, `dev.surefold.kimney`. `group` becomes `dev.surefold`, and the
POM's `url` and `scm` name `github.com/surefold/surefold` (they still name `matthewjones372/lark`).

The last `io.github.matthewjones372` release is followed by one more, at the same version as the first `dev.surefold`
one. Each of its modules is a POM only, with `<relocation><groupId>dev.surefold</groupId></relocation>`, so Maven
and Gradle point anyone resolving the old coordinates at the new ones.

## Why this shape

Coordinates and packages move together, so the name a user depends on and the name they import agree. Moving only
the coordinates would be source-compatible but would leave `io.github.matthewjones372` in every import for good. The
package move is one breaking release, which is what 0.x minor versions are for.

## Stack

- [ ] **`dev-surefold-build`** — `group`, POM `url`/`scm`, plugin IDs, `release.yml`'s Central URL, and `SpecsSuite`/
      docs-fence tests that name the old group. No source moves yet.
      Done when: `./gradlew publishToMavenLocal` writes `~/.m2/repository/dev/surefold/`.
- [ ] **`dev-surefold-packages-<family>`** — one per module family (`core`, `stream`, `actor`, `cluster`, `app`,
      `integrations`, `bench`, `examples`): `git mv` the source trees and rewrite `package`/`import` lines. Mechanical,
      by script; the script is in the PR description.
      Done when: `./gradlew build` is green with no `io.github.matthewjones372` left in that family's sources.
- [ ] **`dev-surefold-docs`** — README, `docs/`, `llms.txt`, the Claude plugin skill: imports, coordinates, plugin IDs.
      Done when: `ReadmeExampleSuite` and the guide suites pass and `grep -r io.github.matthewjones372` finds only
      specs.
- [ ] **`dev-surefold-relocation`** — a `relocation` publication: one POM per module at the old coordinates.
      Done when: a Gradle project depending on `io.github.matthewjones372:surefold:<new>` resolves `dev.surefold:surefold`.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
grep -rn "io.github.matthewjones372" --include=*.kt --include=*.kts --include=*.md . | grep -v '^./specs/'   # empty
./gradlew publishToMavenLocal && ls ~/.m2/repository/dev/surefold/
```

## Open questions

1. The package rename entries run to thousands of changed lines, far over the ~200-line cap, because every file in a
   family moves. **Recommended:** allow it for these mechanical entries, review the script and a sample of files
   rather than every line, and keep one family per PR.
2. surefold's packages drop the repeated name: `dev.surefold.stream`, not `dev.surefold.surefold.stream`.
   **Recommended:** yes, because the namespace already says surefold and sibling libraries sit beside it as
   `dev.surefold.pelican` and `dev.surefold.kimney`. The cost is that a future library cannot be called `stream`,
   `actor` and so on.
3. Version for the move. **Recommended:** the next minor, 0.18.0, for both the first `dev.surefold` release and the
   relocation POMs.
4. Should the relocation release also go out for older versions? **Recommended:** no. One relocation at the new
   version is enough for resolvers to follow.
