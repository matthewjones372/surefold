# JDBC telemetry alignment implementation plan

> Execute this first stack entry inline using the executing-plans skill; stop at the repo's slice review checkpoint.

**Goal:** Align Surefold's OpenTelemetry API and test SDK to the approved 1.67.0 release without changing its tracing behaviour.

**Architecture:** Keep the API-only integration independent of the SDK. Consumers still supply their SDK and exporter; the existing context provider and test collector stay unchanged.

**Tech stack:** Kotlin/JDK 25, Gradle, OpenTelemetry API/context/SDK 1.67.0.

## Files

- Modify `integrations/surefold-otel/build.gradle.kts`: API version only.
- Modify `integrations/surefold-otel-test/build.gradle.kts`: matching SDK version only.
- Modify `docs/modules.md`: published dependency versions.
- Modify `integrations/surefold-otel/src/main/kotlin/io/github/matthewjones372/surefold/otel/TraceCarrier.kt`: remove the deprecated `keys` override; the released getter now defaults it and recommends reading known fields through `get`.
- Reuse existing tests in both integrations and `data/surefold-jobs-otel`; no new behaviour or version-mirroring test.
- Record validation in `docs/reviews/0266-otel-alignment.md`.

## Steps

- [x] Run the unchanged baseline: `./gradlew :surefold-otel:test :surefold-otel-test:test :surefold-jobs-otel:test --offline`.
- [x] Replace `val otelVersion = "1.51.0"` with `val otelVersion = "1.67.0"` in the two named scripts. Replace the two dependency version cells in `docs/modules.md`.
- [x] Reproduce the 1.67 compilation failure at `TextMapGetter.keys`, read the shipped sources, and remove only the deprecated override. Existing message-context tests exercise the retained `get` implementation.
- [x] Run the same tests against 1.67.0, resolving the published artifacts when the offline cache lacks them. Preserve every existing dependency gate except the explicitly owner-approved common artifact amendment.
- [x] Read `dependencyInsight` for API, context and SDK on the API and test-collector runtime classpaths; all resolve to 1.67.0, no SDK on the API main runtime.
- [x] Run formatting, followed by `./gradlew build`. Report failures with evidence; do not change unrelated code or suppress a gate.
- [x] Review the complete first-slice diff and record test counts, compatibility evidence and any measured build bottleneck. Commit only this entry if its checks pass.

Owner-approved amendment `c88840ab` resolves the common artifact contract. The three affected module builds and full serial build pass after the authorised spec 0263 wording repair. See `docs/reviews/0266-otel-alignment.md`.

## Deferred to later approved stack entries

The facade, `DataSource.traced`, disabled identity, privacy tests and benchmark harness belong to their own branches. This entry makes no JDBC or performance-completion claim.
