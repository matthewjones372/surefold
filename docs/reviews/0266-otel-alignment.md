# Spec 0266: OpenTelemetry alignment evidence

The first stack entry is verified on branch `codex/0266-otel-alignment`, based on approved spec `7fbc65a9` and approved dependency amendment `c88840ab`.

## Changes

- API and test SDK 1.51.0 -> 1.67.0; dependency documentation updated.
- Remove private `TextMapGetter.keys` override. The shipped getter deprecates enumeration and defaults it; W3C propagation uses the retained `get` method.
- Permit only the required `opentelemetry-common-` artifact in the two existing classpath guards under amendment `c88840ab`; SDK exclusion remains.
- Replace private application references in spec 0263 with generic application wording, as separately authorised. No credential or ownership behaviour changes.

## Validation

- Unchanged focused baseline passed in 12 seconds (43 tasks: 25 executed, 18 cached).
- Final builds of `surefold-otel`, `surefold-otel-test` and `surefold-jobs-otel` passed in 13 seconds, including all 18 tests, dependency guards, Detekt, formatting and coverage.
- `spotlessApply` passed. The required full build, run as `./gradlew build --offline --no-parallel --max-workers=1`, passed in 25m 13s: 1,274 tasks (510 executed, 57 cached, 707 up-to-date).
- Default-test XML reports contain 3,272 tests, zero failures/errors, one skip. The full build also passed aggregate coverage and the remaining module checks.
- Dependency insight confirms API/context/common and all test SDK artifacts resolve to 1.67.0; API main runtime contains no SDK.
- Independent read-only review found no actionable correctness/scope issue in the version, carrier or guard changes.

The dependency path is `opentelemetry-api -> opentelemetry-context -> opentelemetry-common`, all 1.67.0. Shipped sources use common's ComponentLoader and ApiUsageLogger; excluding it would break the integration. The owner approved the explicit amendment before the guards changed.

## Reuse and build observations

Reuses Surefold context storage, carrier, retry spans and test collector. No extraction candidate or new application mechanism.

A broad parallel run hit timeouts in compiler/database suites; a complete serial run passed without changing timeouts or skipping checks. Contention is a hypothesis, not a measured speedup or confirmed cause of every timeout. Keep unrelated build optimisation separate.

## Remaining

This entry contains no JDBC factory or performance-completion claim. Privacy, JDBC setup, benchmarks and final evidence remain in their approved stack entries. No publication or merge yet.
