# Working in this repo

## Three repositories

Surefold is splitting into three repositories (specs 0301, 0302). Each depends only on the ones above it, through
published artifacts:

| Repository | Holds | May depend on |
|---|---|---|
| `surefold/surefold-core` (this one) | runtime modules on the JDK and Kotlin alone, plus test and build tooling | nothing outside the JDK and Kotlin at runtime |
| `surefold/surefold-integrations` | every module that wraps one outside library | core |
| `surefold/surefold-platform` | the `data/` modules, `surefold-bank`, `surefold-jobs-load`, and `surefold-bom` (0306) | core and integrations |

The platform modules have moved (0301's remove entry). The integrations still live here until 0302's remove entry
lands. Meanwhile:

- **Don't add an outside library to a module that stays in core.** 0302's table says which modules move. A new
  module that wraps a library belongs in `surefold-integrations`; a new platform feature belongs in
  `surefold-platform`.
- **Don't move, copy or delete modules ahead of 0302's stack.** The seed entries carry their history across.
- **Coordinates never change.** Artifacts stay `dev.surefold:<name>`, packages stay `dev.surefold.*`.
- **No Central release for now** (0301 question 5). Downstream repositories pin snapshots from GitHub Packages,
  `https://maven.pkg.github.com/surefold/<repository>`, which `snapshot.yml` publishes on every push to main.
- **Users take one version** through `dev.surefold:surefold-bom` from `surefold-platform` (0306), never three
  versions picked by hand. No Surefold POM uses a version range.
- **Working across repositories:** check them out side by side (`../surefold-core`, `../surefold-integrations`,
  `../surefold-platform`). A downstream build includes the upstream ones it finds there, so a change to core
  reaches platform's tests without publishing. CI never does this: it builds against the pinned snapshots.
- **Specs stay where they were written.** The new repositories number their own from 0001.

## Specs come first

Nothing is implemented without a spec file in `specs/`. An agent drafts it, a
human edits it, and only the edited, committed version gets built.

The draft is a proposal, not a plan. Its job is to be **cheap to disagree
with**, so it stays short enough to read in one sitting:

- **One page.** Roughly 80 lines. A draft that runs longer is proposing too
  much at once — split it into two specs rather than writing more.
- **No prose padding.** Fill the template's headings and stop. Where a heading
  has nothing real under it, write "nothing" and move on.
- **Uncertainty goes under Open questions**, not into a hedged paragraph under
  Shape. Three or four questions in a first draft is healthy.
- **Options, not verdicts.** Where a design could go two ways, give each a
  sentence and recommend one. Do not silently pick.

Then stop and hand it over. Do not implement a spec nobody has edited and
committed: an unreviewed draft is still the agent's own opinion, which is the
thing this process exists to stop.

Before a spec exists: ask questions, read code, answer in chat. No code.

`specs/README.md` gives the layout and the lifecycle.

## One spec section per pull request

Pull requests are stacked. Each branch sits on the one before it and is
reviewable on its own.

- **Soft cap: 200 changed lines** of main sources, build files and docs. Tests,
  generated sources and golden fixtures do not count. Past that, split before
  writing code rather than after.
- **One spec section per PR.** A spec with four stack entries is four branches,
  not one branch with four commits.
- **Announce the split first.** Post the intended stack — branch name and one
  line each — and wait for a yes before the first commit.

Stacking commands (`gh stack init`, `add`, `submit --open`, fixing a lower
entry): [`docs/agents/stacking.md`](docs/agents/stacking.md).

## Layout

Modules are grouped by family into `core/`, `stream/`, `actor/`, `cluster/`, `app/`,
`integrations/`, `bench/` and `examples/` (spec 0139). A module keeps its flat Gradle path,
`:surefold-stream` rather than `:stream:surefold-stream`; `settings.gradle.kts` says which folder it is in.
A new module goes in its family's folder and gets a `module(...)` line there.

One Gradle module per capability, listed in `settings.gradle.kts`;
[`docs/modules.md`](docs/modules.md) says what each is for. `surefold` depends on
the Kotlin standard library and on the JDK, and on nothing else: no HTTP library, no JSON
library, no coroutines, and virtual threads come from `java.lang.Thread`. Every
other module depends on the Surefold modules it builds on and, at most, the one
external library or SDK its name says it integrates: `surefold-pekko` adds
`pekko-actor`, `surefold-stream` adds nothing, `surefold-stream-pekko` adds
`pekko-stream`, `surefold-kafka` adds `kafka-clients`, and so on.

Each claim is a test. `NoOtherDependenciesTest` asserts the module's main
runtime classpath, in every module alike.

A dependency added to any module is a build failure, not a judgement call.

Adding a module: [`docs/agents/new-module.md`](docs/agents/new-module.md) lists
every place that has to name it.

## Coding standards

Comments, imports, errors, effects and read-only API:
[`CODING_STANDARDS.md`](CODING_STANDARDS.md). Review enforces it.

## Testing

Write the failing test first. Most claims here are claims about agreement
between the route, the document, the test client and the generated client, and
a test written afterwards asserts what the implementation does rather than what
the description promised.

Test names are sentences in backticks. `surefold-assert` matchers, JUnit 5, `withClue`
where a bare boolean would not explain itself.

Work out which of these a change can break:

- **Thread claims.** A test that says the body ran on a virtual thread reads
  `Thread.currentThread().isVirtual` inside the body and asserts on it
  afterwards; it does not count threads or time sleeps.

### Examples in the docs are compiled

The guides' Kotlin fences are compiled by tests that read the Markdown:
`CoreGuidesSuite` in `core/surefold-guides` (`docs/core.md`, `testing.md` and
the README's suite, against the core alone), `ReadmeExampleSuite` (README and
`docs/stream.md`), `CoreGuideTest` and `CookbookTest` (`observability.md`,
`arrow.md`, `flags.md`, `cookbook.md`), `ActorsGuideTest` and
`GuideExampleTest`. A fence is found by
the HTML comment above it, such as `<!-- core-policy -->`, so keep the marker
when editing an example, and add one to a new complete example. Each module's
`build.gradle.kts` declares the pages it reads as test inputs.

### An actor system belongs to the test class

`surefold-pekko` and `surefold-stream-pekko` tests need an `ActorSystem`. It is created in a
`companion object` from a config string and stopped in `@AfterAll`, never by
hand inside a test and never shared across classes — a system left running
holds threads that make the next suite's thread assertions lie.

Kover has a floor of 90% on `check`.

## Verifying

`./gradlew build` runs tests, detekt and spotless. Run it before saying
anything is done, and quote the result rather than predicting it.

**Finish with `./gradlew spotlessApply`.** Formatting is a gate like any other,
and a change that is correct but unformatted fails the build for whoever runs
it next. Run it last, after the final edit, so nothing lands unformatted:

```bash
./gradlew spotlessApply && ./gradlew build
```

A pull request's CI builds less than this: the modules it changes, everything
that depends on them, and `surefold-app`'s suites, from
`.github/affected-tasks.sh`, or `SpecsSuite` alone for a change only to `specs/`.
Run the script with the base (`origin/main`) to
see that list. A push to main still runs the whole build, the coverage floor
across every module included.

One Gradle build at a time per checkout. Two at once fail with timeouts and
missing test results that look like real failures.

Three gates sit beyond the tests. Each exists because a claim in the README
would otherwise be unverified.

| Gate | Fails when | Not the fix |
|---|---|---|
| detekt | any finding, or a source it cannot compile (`X.serializer()`: write `serializer<X>()`) | a suppression with no reason |
| `NoOtherDependenciesTest` | anything but what the module is allowed on the runtime classpath | widening the allow-list |
| Kover | line coverage under 90% | lowering the floor |

Before saying it is done:

- The failing test came first, and fails without the change.
- `./gradlew build` is green, gates included.
- No new dependency in `surefold`.
- The README reflects the change, or the change is invisible from outside.

## Agent skills

### Issue tracker

Specs are the tickets: a spec's Stack entries are its work items, in blocking
order. Other issues live in GitHub Issues for `surefold/surefold-core`, via the
`gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five-role vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
