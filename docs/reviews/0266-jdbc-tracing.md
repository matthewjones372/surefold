# Spec 0266 verification

OpenTelemetry API/test SDK 1.67.0 and JDBC instrumentation 2.33.1-alpha are pinned. The optional module supplies no SDK, driver or pool to applications. Disabled mode returns the original source.

Privacy and JDBC checks cover sampler/processor/exporter boundaries, native execution-time parents, operation-only fallback, JDBC results/errors, builders/default methods and critical metadata callbacks. The five convention JVMs also test held successful/failing exporters: 5632 updates complete while each queue stays at 4096, with export off query threads.

The installed recording workload passed a functional macOS smoke: 1,348,523 measured operations, all 2,068,593 warm-up-plus-measurement spans delivered, and successful flush. The temporary database/container was removed. This run is unqualified and supplies no performance-gate result.

The runner's 18 standard-library tests cover statistical thresholds, uncertainty, wrong targets/results, failed/timeout/interrupted attempt retention and fixed-seed repeatability. The setup guide is matched against compiled Kotlin. Final repository verification is recorded in the commit validation.

The earlier complete implementation build passed in 13m 42s (1,442 tasks; 3,324 default tests, zero failures/errors, one skip). One prior run timed out in the unchanged cluster ProducersSuite; its three cases passed in isolation and its full module passed in the successful retry. No timeout or check was weakened; the original timeout's cause remains unconfirmed.

Reuse: public JdbcTelemetry interception plus Surefold context storage, SurefoldLocal, Shared, Promise, parMap and shared test resources. No application policy or extraction candidate remains outside Surefold. Source-only diagnostics stay outside the two-percent gate.

Qualified ten-pair measurements remain pending the owner's dedicated Linux amd64 machine details. No zero-overhead, two-percent, publication or merge claim is made; retain every future attempt and raw artifact before reviewing that gate.
